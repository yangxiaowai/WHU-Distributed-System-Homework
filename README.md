# 商品库存与秒杀系统作业

本项目用于完成“分布式软件原理与技术”课程作业，当前已经覆盖截图中的核心验收点：

- Redis 商品详情缓存
- Redis 库存缓存 + Lua 原子预扣减
- Kafka 异步创建订单，削峰填谷
- Snowflake 订单 ID 生成
- 同一用户同一商品只能秒杀一次
- 最终库存不超卖、订单数据完整
- Nginx 负载均衡 + 动静分离
- MySQL 主从复制 + 代码层读写分离
- 可选 Elasticsearch 商品搜索

## 目录说明

- `docs/design.md`：系统设计文档
- `docker-compose.yml`：一键启动完整验收环境
- `sql/init.sql`：数据库初始化脚本
- `mysql/`：MySQL 主从复制脚本
- `nginx/`：Nginx 负载均衡和静态资源
- `jmeter/`：压测脚本

## 技术架构

```text
Browser
   |
 Nginx(80)
   |---- /           -> 静态页面
   |---- /api/*      -> app1(8081) / app2(8082)
                         |---- MySQL Primary(3306)
                         |---- MySQL Replica(3307)
                         |---- Redis(6379)
                         |---- Kafka(9092)
                         |---- Elasticsearch(9200, 可选)
```

核心写链路如下：

1. 秒杀请求先在 Redis 中执行 Lua 脚本，原子完成“查重 + 判断库存 + 预扣减库存”。
2. 预扣成功后生成 Snowflake 订单号，并把下单消息投递到 Kafka。
3. Kafka 消费者在本地事务中扣减 MySQL `stock.available` 并写入 `seckill_order`。
4. 如果事务失败、库存不足或发现重复订单，消费者会补偿 Redis 预占状态并写失败状态。
5. 客户端可按 `orderId` 或 `userId` 查询订单/排队结果。

## 启动方式

### 1. Docker 一键启动

```bash
docker compose up -d --build
```

项目默认已启用国内镜像加速：

- Docker 镜像前缀默认：`docker.m.daocloud.io`
- Maven 依赖仓库默认：`https://maven.aliyun.com/repository/public`

默认暴露端口：

- `80`：Nginx 统一入口
- `3306`：MySQL 主库
- `3307`：MySQL 从库
- `6379`：Redis
- `9092`：Kafka
- `9200`：Elasticsearch（可选）
- `8081` / `8082`：两个 Spring Boot 实例

### 2. 本地运行后端

```bash
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="\
  -Dspring.datasource.write.url=jdbc:mysql://localhost:3306/seckill?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai \
  -Dspring.datasource.read.url=jdbc:mysql://localhost:3307/seckill?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai \
  -Dspring.data.redis.host=localhost \
  -Dspring.kafka.bootstrap-servers=localhost:9092 \
  -Dseckill.search.es.base-url=http://localhost:9200"
```

如果本地没有启动 Kafka，可临时切回同步落单：

```bash
-Dseckill.order.async-enabled=false
```

如果本地没有启动 ES，可额外追加：

```bash
-Dseckill.search.es.enabled=false
```

## 作业验收步骤

### 1. 注册用户

注册接口现在会直接返回 `userId`，便于秒杀下单验收：

```bash
curl -X POST http://localhost/api/users/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"123456","phone":"13800000000"}'
```

示例返回：

```json
{"code":0,"msg":"success","data":1}
```

如果要按用户名回查用户：

```bash
curl "http://localhost/api/users/by-username?username=alice"
```

### 2. 查看库存

```bash
curl http://localhost/api/stocks/3
```

示例返回中：

- `available`：MySQL 当前可用库存
- `redisAvailable`：Redis 当前缓存库存

### 3. 发起秒杀

```bash
curl -X POST http://localhost/api/orders/seckill \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":3,"amount":1}'
```

正常情况下立即返回：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "orderId": 297605529499447296,
    "orderNo": "297605529499447296",
    "status": "PENDING",
    "message": "秒杀请求已受理，订单正在异步创建"
  }
}
```

### 4. 查询订单

按订单 ID 查询：

```bash
curl http://localhost/api/orders/297605529499447296
```

按用户 ID 查询：

```bash
curl "http://localhost/api/orders?userId=1"
```

消费者成功落库后，订单状态会变为 `CREATED`。

### 5. 验证幂等性

同一用户再次秒杀同一商品：

```bash
curl -X POST http://localhost/api/orders/seckill \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"productId":3,"amount":1}'
```

接口不会重复创建订单，而是直接返回已有订单状态。

### 6. 验证库存一致性

多次并发请求秒杀库存很小的商品后，执行：

```bash
curl http://localhost/api/stocks/3
curl "http://localhost/api/orders?userId=1"
```

检查点：

- Redis 库存不会被扣成负数
- MySQL `stock.available` 不会小于 0
- 每个成功订单都能在 `seckill_order` 表和查询接口中看到

### 7. 读场景验收

#### 7.1 负载均衡

```bash
curl http://localhost/api/meta/whoami
curl http://localhost/api/meta/whoami
curl http://localhost/api/meta/whoami
```

返回中的 `port` 会在 `8081` / `8082` 之间切换。

#### 7.2 商品详情缓存

```bash
curl http://localhost/api/products/1
curl http://localhost/api/products/1
```

第一次回源数据库，后续请求命中 Redis。

#### 7.3 缓存穿透

```bash
curl http://localhost/api/products/999999
curl http://localhost/api/products/999999
```

不存在的商品会写入空值缓存，避免持续穿透数据库。

#### 7.4 缓存击穿

热点商品详情采用互斥锁 + 双重检查，避免大量线程同时打到数据库。

#### 7.5 缓存雪崩

商品缓存和空值缓存均带随机 TTL 抖动，避免大批 key 同时过期。

#### 7.6 缓存一致性

```bash
curl -X PUT http://localhost/api/products/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"测试商品A-更新","description":"更新后用于验证缓存失效","price":29.90,"status":1}'
```

更新商品后会主动删除详情缓存，下一次读取重新回源。

### 8. MySQL 读写分离

```bash
curl http://localhost/api/meta/db/write
curl http://localhost/api/meta/db/read
```

预期：

- `/api/meta/db/write` 返回 `read_only = 0`
- `/api/meta/db/read` 返回 `read_only = 1`

### 9. Elasticsearch 商品搜索（可选）

```bash
docker compose --profile es up -d
curl "http://localhost/api/products/search/reindex"
curl "http://localhost/api/products/search?q=测试&size=10"
```

## 主要接口

- `POST /api/users/register`：注册用户，返回 `userId`
- `POST /api/users/login`：用户登录
- `GET /api/users/{id}`：按用户 ID 查询
- `GET /api/users/by-username`：按用户名查询
- `GET /api/products/list`：商品列表
- `GET /api/products/{id}`：商品详情
- `PUT /api/products/{id}`：更新商品并清理缓存
- `DELETE /api/products/{id}/cache`：手动清理单个商品缓存
- `GET /api/stocks/{productId}`：查看库存快照
- `POST /api/stocks/{productId}/sync-cache`：把库存同步到 Redis
- `POST /api/orders/seckill`：发起秒杀
- `GET /api/orders/{orderId}`：按订单 ID 查询
- `GET /api/orders?userId=1`：按用户 ID 查询订单
- `GET /api/meta/whoami`：查看当前实例
- `GET /api/meta/db/write`：查看主库元信息
- `GET /api/meta/db/read`：查看从库元信息
- `GET /api/products/search`：ES 搜索
- `GET /api/products/search/reindex`：ES 全量重建索引

## 关键实现说明

### 1. 秒杀写链路

- Redis Lua 脚本保证预扣库存和重复下单检查是原子操作。
- Kafka 只承接“创建订单”消息，前端请求不会同步阻塞在数据库写入上。
- 订单创建使用 Snowflake 生成全局唯一订单号，双实例下也不会冲突。

### 2. 幂等性

- Redis `seckill:order:user:{userId}:{productId}` 防止同一用户重复抢购。
- MySQL `seckill_order` 表上 `UNIQUE(user_id, product_id)` 作为最终兜底。
- 即使 Redis 丢失状态，数据库唯一约束仍能阻止重复订单。

### 3. 数据一致性

- Redis 先预扣，Kafka 消费者再在事务中扣减 MySQL 库存并写订单。
- 如果消费者发现库存不足、重复订单或事务失败，会补偿 Redis 预占状态。
- 这样可以保证最终不会超卖，且订单数据完整可查。

### 4. 高并发读能力

- 商品详情采用 Cache Aside 模式。
- 通过空值缓存、互斥锁和随机 TTL 分别处理缓存穿透、击穿、雪崩。
- 商品读请求默认走从库，写请求走主库。

## 压测

- 读场景压测文件：`jmeter/seckill-read-loadtest.jmx`
- 写场景可使用 JMeter / wrk / ApacheBench 对 `POST /api/orders/seckill` 做并发压测

命令行执行读压测：

```bash
mkdir -p results
jmeter -n -t jmeter/seckill-read-loadtest.jmx -l results/seckill-read-loadtest.jtl
```

## 选做：分库分表

分库分表属于截图中的选做项，当前代码未接入 ShardingSphere。`docs/design.md` 中已经补充推荐接入方案，可作为继续扩展的实现蓝图。
