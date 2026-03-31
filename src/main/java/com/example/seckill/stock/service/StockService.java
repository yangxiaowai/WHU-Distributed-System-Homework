package com.example.seckill.stock.service;

import com.example.seckill.stock.domain.Stock;
import com.example.seckill.stock.dto.StockSnapshot;
import com.example.seckill.stock.mapper.StockMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StockService {

    private static final String STOCK_CACHE_KEY_PREFIX = "seckill:stock:";

    private final StockMapper stockMapper;
    private final StringRedisTemplate redisTemplate;

    public StockService(StockMapper stockMapper, StringRedisTemplate redisTemplate) {
        this.stockMapper = stockMapper;
        this.redisTemplate = redisTemplate;
    }

    public Stock getStockByProductId(Long productId) {
        if (productId == null || productId <= 0) {
            return null;
        }
        return stockMapper.findByProductId(productId);
    }

    public List<Stock> listAllStocks() {
        return stockMapper.listAll();
    }

    public StockSnapshot getStockSnapshot(Long productId) {
        Stock stock = getStockByProductId(productId);
        if (stock == null) {
            return null;
        }
        return new StockSnapshot(
                stock.getProductId(),
                stock.getTotal(),
                stock.getAvailable(),
                getCachedAvailable(productId)
        );
    }

    public void syncStockCache(Long productId) {
        Stock stock = getStockByProductId(productId);
        if (stock == null) {
            deleteStockCache(productId);
            return;
        }
        writeStockCache(productId, stock.getAvailable());
    }

    public void syncAllStockCache() {
        for (Stock stock : listAllStocks()) {
            writeStockCache(stock.getProductId(), stock.getAvailable());
        }
    }

    public Integer getCachedAvailable(Long productId) {
        try {
            String cacheValue = redisTemplate.opsForValue().get(buildStockCacheKey(productId));
            return cacheValue == null ? null : Integer.parseInt(cacheValue);
        } catch (DataAccessException | NumberFormatException ignored) {
            return null;
        }
    }

    public String buildStockCacheKey(Long productId) {
        return STOCK_CACHE_KEY_PREFIX + productId;
    }

    private void writeStockCache(Long productId, Integer available) {
        try {
            redisTemplate.opsForValue().set(buildStockCacheKey(productId), String.valueOf(available));
        } catch (DataAccessException ignored) {
        }
    }

    private void deleteStockCache(Long productId) {
        try {
            redisTemplate.delete(buildStockCacheKey(productId));
        } catch (DataAccessException ignored) {
        }
    }
}
