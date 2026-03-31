package com.example.seckill.stock.dto;

public record StockSnapshot(Long productId,
                            Integer total,
                            Integer available,
                            Integer redisAvailable) {
}
