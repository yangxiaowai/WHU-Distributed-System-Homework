CREATE DATABASE IF NOT EXISTS seckill CHARACTER SET utf8mb4;

USE seckill;

-- Ensure utf8mb4 literal decoding (avoid mojibake when SQL file is parsed)
SET NAMES utf8mb4;
SET character_set_client = utf8mb4;

CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `username` VARCHAR(50) NOT NULL UNIQUE,
    `password` VARCHAR(100) NOT NULL,
    `phone` VARCHAR(20),
    `status` TINYINT DEFAULT 1,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS `product` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `name` VARCHAR(100) NOT NULL,
    `description` VARCHAR(255),
    `price` DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    `status` TINYINT DEFAULT 1,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS `stock` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `product_id` BIGINT NOT NULL,
    `total` INT NOT NULL,
    `available` INT NOT NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_stock_product_id` (`product_id`)
);

CREATE TABLE IF NOT EXISTS `seckill_order` (
    `id` BIGINT PRIMARY KEY,
    `order_no` VARCHAR(64) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `product_id` BIGINT NOT NULL,
    `amount` INT NOT NULL DEFAULT 1,
    `status` TINYINT NOT NULL DEFAULT 0,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_user_product` (`user_id`, `product_id`),
    KEY `idx_order_user_id` (`user_id`)
);

INSERT INTO `product` (`id`, `name`, `description`, `price`, `status`)
VALUES
    (1, '测试商品A', '用于缓存/负载均衡/压测演示', 9.90, 1),
    (2, '测试商品B', '用于缓存穿透（不存在ID）对比', 19.90, 1),
    (3, '秒杀测试商品C', '用于秒杀异步下单演示', 29.90, 1)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `description` = VALUES(`description`),
    `price` = VALUES(`price`),
    `status` = VALUES(`status`);

INSERT INTO `stock` (`id`, `product_id`, `total`, `available`, `version`)
VALUES
    (1, 1, 50, 50, 0),
    (2, 2, 30, 30, 0),
    (3, 3, 20, 20, 0)
ON DUPLICATE KEY UPDATE
    `total` = VALUES(`total`),
    `available` = VALUES(`available`),
    `version` = VALUES(`version`);
