package com.example.seckill.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class UpdateProductRequest {

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 100, message = "商品名称长度不能超过100")
    private String name;

    @Size(max = 255, message = "商品描述长度不能超过255")
    private String description;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.00", inclusive = true, message = "商品价格不能小于0")
    private BigDecimal price;

    @NotNull(message = "商品状态不能为空")
    @Min(value = 0, message = "商品状态只能为0或1")
    @Max(value = 1, message = "商品状态只能为0或1")
    private Integer status;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
