package com.example.seckill.stock.controller;

import com.example.seckill.stock.dto.StockSnapshot;
import com.example.seckill.stock.service.StockService;
import com.example.seckill.user.controller.UserController.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping("/{productId}")
    public ApiResponse<StockSnapshot> getStock(@PathVariable("productId") Long productId) {
        StockSnapshot snapshot = stockService.getStockSnapshot(productId);
        if (snapshot == null) {
            return ApiResponse.fail("库存不存在");
        }
        return ApiResponse.success(snapshot);
    }

    @PostMapping("/{productId}/sync-cache")
    public ApiResponse<Void> syncStockCache(@PathVariable("productId") Long productId) {
        stockService.syncStockCache(productId);
        return ApiResponse.success();
    }
}
