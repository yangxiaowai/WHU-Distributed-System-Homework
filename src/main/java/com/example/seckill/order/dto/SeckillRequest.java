package com.example.seckill.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class SeckillRequest {

    @NotNull(message = "userId 不能为空")
    @Positive(message = "userId 必须大于 0")
    private Long userId;

    @NotNull(message = "productId 不能为空")
    @Positive(message = "productId 必须大于 0")
    private Long productId;

    @NotNull(message = "amount 不能为空")
    @Min(value = 1, message = "秒杀数量只能为 1")
    @Max(value = 1, message = "秒杀数量只能为 1")
    private Integer amount = 1;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getAmount() {
        return amount;
    }

    public void setAmount(Integer amount) {
        this.amount = amount;
    }
}
