## 商品库存与秒杀系统设计文档

### 1. 目标

本项目对应课程作业中的“高并发写”与“高并发读”两个方向，当前实现目标如下：

- 秒杀请求具备高并发入口承载能力
- 下单链路支持 Redis 预扣库存 + Kafka 异步落单
- 订单号使用 Snowflake 生成
- 同一用户同一商品只能秒杀一次
- 最终库存不超卖，订单数据完整
- 商品详情读场景支持缓存、读写分离与负载均衡

### 2. 整体架构

```text
Client
  |
Nginx
  |
  +-- Spring Boot app1 / app2
         |
         +-- Redis
         +-- Kafka
         +-- MySQL Primary
         +-- MySQL Replica
         +-- Elasticsearch(optional)
```

组件职责：

- Nginx：统一入口、负载均衡、动静分离
- Spring Boot：承载用户、商品、库存、订单、元信息接口
- Redis：商品详情缓存、库存缓存、秒杀幂等标记、订单状态缓存
- Kafka：异步创建订单，削峰填谷
- MySQL Primary：订单与库存写入
- MySQL Replica：商品读请求
- Elasticsearch：商品搜索（选配）

### 3. 数据模型

#### 3.1 `user`

- `id`：用户 ID
- `username`：用户名，唯一
- `password`：BCrypt 加密密码
- `phone`：手机号
- `status`：状态

#### 3.2 `product`

- `id`：商品 ID
- `name`：商品名称
- `description`：描述
- `price`：价格
- `status`：上架状态

#### 3.3 `stock`

- `product_id`：商品 ID，唯一
- `total`：总库存
- `available`：当前可用库存
- `version`：版本号

#### 3.4 `seckill_order`

- `id`：订单 ID，使用 Snowflake
- `order_no`：业务订单号
- `user_id`：用户 ID
- `product_id`：商品 ID
- `amount`：秒杀数量，当前限制为 1
- `status`：`0-CREATED`、`1-PAID`、`2-CANCELLED`

约束设计：

- `UNIQUE(order_no)`：保证订单号唯一
- `UNIQUE(user_id, product_id)`：保证同一用户同一商品只能下单一次

### 4. 秒杀核心流程

#### 4.1 请求入口

客户端调用：

```text
POST /api/orders/seckill
```

请求体：

```json
{
  "userId": 1,
  "productId": 3,
  "amount": 1
}
```

#### 4.2 时序

1. 服务端校验用户、商品、库存记录是否存在。
2. 生成 Snowflake `orderId` / `orderNo`。
3. 执行 Redis Lua 脚本：
   - 检查用户是否已经抢过该商品
   - 检查 Redis 库存是否充足
   - `decrby` 预扣库存
   - 写入用户秒杀标记
   - 写入订单状态为 `PENDING`
4. 预扣成功后把消息发送到 Kafka `seckill-order-create`。
5. Kafka 消费者收到消息，在本地事务中：
   - 再次检查数据库里是否已有该用户该商品的订单
   - 扣减 MySQL `stock.available`
   - 插入 `seckill_order`
6. 如果事务成功：
   - Redis 状态更新为 `CREATED`
   - 保留用户秒杀标记
7. 如果事务失败、库存不足或检测到重复订单：
   - 补偿 Redis 库存
   - 清理当前请求的用户占位
   - 订单状态更新为 `FAILED`

### 5. 一致性与幂等性设计

#### 5.1 幂等性

第一层：Redis 快速幂等

- Key：`seckill:order:user:{userId}:{productId}`
- 作用：在流量入口处快速拦截重复秒杀

第二层：数据库最终幂等

- `UNIQUE(user_id, product_id)` 约束
- 即使 Redis 状态丢失，也不会插入重复订单

#### 5.2 最终一致性

本项目采用“Redis 预扣 + MQ 异步落库 + 失败补偿”的最终一致性模式：

- 用户请求成功返回时，表示请求已进入可靠处理链路
- 订单实际写库由 Kafka 消费者异步完成
- 查询接口支持读取 Redis 中的 `PENDING` / `FAILED` 状态
- 一旦落库成功，订单转为数据库事实数据

#### 5.3 防超卖

防超卖采用双保险：

- Redis Lua 脚本提前限制库存不能扣成负数
- MySQL 更新语句带条件：`available >= amount`

这样即便出现缓存重建或实例切换，数据库层仍会阻止超卖。

### 6. 高并发读设计

#### 6.1 商品详情缓存

- 模式：Cache Aside
- Key：`product:detail:{id}`
- 空值缓存：防缓存穿透
- 互斥锁：防缓存击穿
- TTL 抖动：防缓存雪崩

#### 6.2 读写分离

- 写请求默认走主库
- 标注 `@ReadOnly` 的查询走从库
- 通过 AOP + 动态数据源实现路由

#### 6.3 负载均衡

- Nginx 轮询转发到 `app1`、`app2`
- 静态页面由 Nginx 直接返回，动态请求转发到后端

### 7. REST API 设计

#### 7.1 用户

- `POST /api/users/register`
- `POST /api/users/login`
- `GET /api/users/{id}`
- `GET /api/users/by-username`

#### 7.2 商品

- `GET /api/products/list`
- `GET /api/products/{id}`
- `PUT /api/products/{id}`
- `DELETE /api/products/{id}/cache`

#### 7.3 库存

- `GET /api/stocks/{productId}`
- `POST /api/stocks/{productId}/sync-cache`

#### 7.4 订单 / 秒杀

- `POST /api/orders/seckill`
- `GET /api/orders/{orderId}`
- `GET /api/orders?userId=1`

### 8. 异常场景处理

#### 8.1 Kafka 投递失败

- 如果 Kafka 明确返回发送失败，立即补偿 Redis 预占库存
- 如果投递超时，则保留 `PENDING` 状态，由客户端稍后查单

#### 8.2 Redis 缓存缺失

- 如果 Redis 中没有该商品库存，会先从 MySQL 同步到 Redis 再重试一次

#### 8.3 消费者重复消费

- 先查订单主键和 `(user_id, product_id)` 唯一键
- 若订单已存在，不会重复扣库存或重复插单

### 9. 可选扩展：分库分表

截图中的分库分表属于选做项，推荐扩展方式如下：

- 组件：ShardingSphere-JDBC 或 ShardingSphere-Proxy
- 分库键：`user_id`
- 分表键：`order_id`

示意：

```text
db_0.seckill_order_0
db_0.seckill_order_1
db_1.seckill_order_0
db_1.seckill_order_1
```

路由策略建议：

- 数据库路由：`user_id % 2`
- 表路由：`order_id % 2`

接入步骤：

1. 新增两个订单库 `seckill_order_db_0`、`seckill_order_db_1`
2. 每个库中拆分两张表 `seckill_order_0`、`seckill_order_1`
3. 把当前 `SeckillOrderMapper` 改为走 ShardingSphere 数据源
4. 查询接口继续保留按 `orderId`、`userId` 的查询入口

当前仓库暂未把这一部分接入运行时代码，因为它属于课程选做项，但现有订单表结构与 ID 方案已兼容后续演进。
