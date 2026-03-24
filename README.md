# 商品库存与秒杀系统作业

本项目用于完成“分布式软件原理与技术”课程中“高并发读”相关作业。当前代码已经覆盖作业截图中的主要验收点：

- Nginx 负载均衡 + 动静分离
- Redis 商品详情页缓存
- 缓存穿透、击穿、雪崩治理
- MySQL 主从复制 + 代码层读写分离
- 可选 Elasticsearch 商品搜索
- JMeter 读场景压测脚本

## 目录说明

- `docs/design.md`：系统设计文档
- `docker-compose.yml`：一键启动完整验收环境
- `sql/init.sql`：数据库初始化脚本
- `mysql/`：MySQL 主从复制脚本
- `nginx/`：Nginx 负载均衡和静态资源
- `jmeter/`：JMeter 压测脚本

## 技术架构

整体部署结构如下：

```text
Browser
   |
 Nginx(80)
   |---- /           -> 静态页面
   |---- /api/*      -> app1(8081) / app2(8082)
                         |---- MySQL Primary(3306)
                         |---- MySQL Replica(3307)
                         |---- Redis(6379)
                         |---- Elasticsearch(9200, 可选)
```

后端使用 Spring Boot + MyBatis 实现，商品读请求默认走从库，写请求默认走主库，商品详情采用 Cache Aside 模式读 Redis。

## 启动方式

### 1. Docker 一键启动

在项目根目录执行：

```bash
docker compose up -d --build
```

启动后默认暴露端口：

- `80`：Nginx 统一入口
- `3306`：MySQL 主库
- `3307`：MySQL 从库
- `6379`：Redis
- `9200`：Elasticsearch
- `8081` / `8082`：两个 Spring Boot 实例

### 2. 本地运行后端（可选）

如果你想直接在 IDE 中运行：

```bash
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="\
  -Dspring.datasource.write.url=jdbc:mysql://localhost:3306/seckill?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai \
  -Dspring.datasource.read.url=jdbc:mysql://localhost:3307/seckill?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai \
  -Dspring.data.redis.host=localhost \
  -Dseckill.search.es.base-url=http://localhost:9200"
```

如果本地没有启动 ES，可额外追加：

```bash
-Dseckill.search.es.enabled=false
```

## 作业验收步骤

### 1. 负载均衡 + 动静分离

- 静态首页：`GET http://localhost/`
- 动态接口：`GET http://localhost/api/products/1`
- 负载均衡验证：多次请求 `GET http://localhost/api/meta/whoami`

示例：

```bash
curl http://localhost/api/meta/whoami
curl http://localhost/api/meta/whoami
curl http://localhost/api/meta/whoami
```

返回中的 `port` 会在 `8081` 和 `8082` 之间切换，说明 Nginx 已经把请求分发到不同实例。

### 2. Redis 分布式缓存

商品详情接口：

- `GET /api/products/{id}`：查询商品详情
- `DELETE /api/products/{id}/cache`：手动清理单个商品缓存
- `PUT /api/products/{id}`：更新商品信息，并主动删除旧缓存

#### 2.1 缓存命中

```bash
curl http://localhost/api/products/1
curl http://localhost/api/products/1
```

第一次请求会回源数据库并写入 Redis，后续请求直接命中缓存。

#### 2.2 缓存穿透

```bash
curl http://localhost/api/products/999999
curl http://localhost/api/products/999999
```

不存在的商品会写入 `NULL` 占位缓存，避免恶意请求持续穿透数据库。

#### 2.3 缓存击穿

热点商品详情采用互斥锁 + 双重检查：

- 只有一个线程能拿到 Redis 锁并回源数据库
- 其他线程短暂等待后重试读取缓存
- 如果 Redis 异常，会降级直接查数据库，避免接口整体不可用

#### 2.4 缓存雪崩

缓存写入时会在基础 TTL 上加入随机抖动，避免大量 key 同时过期：

- 正常商品缓存：`30 分钟 + 随机秒数`
- 空值缓存：`2 分钟 + 随机秒数`

#### 2.5 缓存一致性

更新商品后会主动删除对应详情缓存，下一次读取重新回源并重建缓存：

```bash
curl -X PUT http://localhost/api/products/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"测试商品A-更新","description":"更新后用于验证缓存失效","price":29.90,"status":1}'

curl http://localhost/api/products/1
```

### 3. MySQL 读写分离

代码中通过 `@ReadOnly` 注解 + AOP + 动态数据源完成读写路由：

- 写请求默认走主库
- 标记为 `@ReadOnly` 的查询走从库

验证接口：

- `GET /api/meta/db/write`：强制访问主库，期望 `read_only = 0`
- `GET /api/meta/db/read`：强制访问从库，期望 `read_only = 1`

```bash
curl http://localhost/api/meta/db/write
curl http://localhost/api/meta/db/read
```

也可以结合商品接口观察业务层面的读写路径：

- `PUT /api/products/{id}`：写主库
- `GET /api/products/{id}`：读缓存，缓存未命中时读从库

### 4. Elasticsearch 商品搜索（可选）

接口如下：

- `GET /api/products/search?q=测试&size=10`
- `GET /api/products/search/reindex`

验证命令：

```bash
curl "http://localhost/api/products/search/reindex"
curl "http://localhost/api/products/search?q=测试&size=10"
curl "http://localhost:9200/products/_search?q=name:测试商品"
```

如果不需要 ES，可在运行时把 `SECKILL_SEARCH_ES_ENABLED` 设为 `false`。

## 主要接口

- `POST /api/users/register`：用户注册
- `POST /api/users/login`：用户登录
- `GET /api/products/list`：商品列表
- `GET /api/products/{id}`：商品详情
- `PUT /api/products/{id}`：更新商品并清理缓存
- `DELETE /api/products/{id}/cache`：手动清理缓存
- `GET /api/meta/whoami`：查看当前实例
- `GET /api/meta/db/write`：查看主库元信息
- `GET /api/meta/db/read`：查看从库元信息
- `GET /api/products/search`：ES 搜索
- `GET /api/products/search/reindex`：ES 全量重建索引

## 压测

压测文件：`jmeter/seckill-read-loadtest.jmx`

命令行运行：

```bash
mkdir -p results
jmeter -n -t jmeter/seckill-read-loadtest.jmx -l results/seckill-read-loadtest.jtl
```

建议压测流程：

1. 先清理目标商品缓存。
2. 进行一轮预热请求，让热点商品进入缓存。
3. 再执行 JMeter 读压测，对比缓存前后的吞吐和响应时间。

## 关键实现说明

- 商品详情缓存使用 Cache Aside 模式，核心代码在 `src/main/java/com/example/seckill/product/service/ProductService.java`
- 读写分离核心代码在 `src/main/java/com/example/seckill/config/datasource/`
- 读写分离验证接口在 `src/main/java/com/example/seckill/meta/`
- 商品搜索实现位于 `src/main/java/com/example/seckill/product/search/`
