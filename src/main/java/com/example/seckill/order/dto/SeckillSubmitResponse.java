package com.example.seckill.order.dto;

public record SeckillSubmitResponse(Long orderId,
                                    String orderNo,
                                    String status,
                                    String message) {
}
