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

INSERT INTO `product` (`id`, `name`, `description`, `price`, `status`)
VALUES
    (1, '测试商品A', '用于缓存/负载均衡/压测演示', 9.90, 1),
    (2, '测试商品B', '用于缓存穿透（不存在ID）对比', 19.90, 1)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `description` = VALUES(`description`),
    `price` = VALUES(`price`),
    `status` = VALUES(`status`);

