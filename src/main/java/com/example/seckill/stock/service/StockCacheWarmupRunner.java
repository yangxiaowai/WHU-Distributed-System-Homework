package com.example.seckill.stock.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StockCacheWarmupRunner implements ApplicationRunner {

    private final StockService stockService;

    public StockCacheWarmupRunner(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public void run(ApplicationArguments args) {
        stockService.syncAllStockCache();
    }
}
