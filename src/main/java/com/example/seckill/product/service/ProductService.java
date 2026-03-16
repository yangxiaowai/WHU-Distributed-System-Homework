package com.example.seckill.product.service;

import com.example.seckill.product.domain.Product;
import com.example.seckill.product.mapper.ProductMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Random;

@Service
public class ProductService {

    private static final String PRODUCT_CACHE_KEY_PREFIX = "product:detail:";
    private static final String PRODUCT_NULL_VALUE = "NULL";

    private final ProductMapper productMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    public ProductService(ProductMapper productMapper,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Product getProductById(Long id) {
        String cacheKey = PRODUCT_CACHE_KEY_PREFIX + id;
        ValueOperations<String, String> ops = redisTemplate.opsForValue();

        // 1. 先查缓存（Cache Aside 读路径）
        String cacheValue = ops.get(cacheKey);
        if (cacheValue != null) {
            if (PRODUCT_NULL_VALUE.equals(cacheValue)) {
                // 缓存穿透保护：数据库中不存在的数据直接返回 null
                return null;
            }
            try {
                return objectMapper.readValue(cacheValue, Product.class);
            } catch (JsonProcessingException ignored) {
                // 反序列化失败则降级走数据库
            }
        }

        // 2. 使用简单的分布式锁处理热点 Key 缓存击穿
        String lockKey = cacheKey + ":lock";
        boolean lockAcquired = false;
        try {
            lockAcquired = Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(5))
            );
        } catch (DataAccessException ignored) {
        }

        if (lockAcquired) {
            try {
                // 双重检查：拿到锁后再次检查缓存，防止重复加载
                cacheValue = ops.get(cacheKey);
                if (cacheValue != null) {
                    if (PRODUCT_NULL_VALUE.equals(cacheValue)) {
                        return null;
                    }
                    try {
                        return objectMapper.readValue(cacheValue, Product.class);
                    } catch (JsonProcessingException ignored) {
                        return null;
                    }
                }

                // 3. 查数据库
                Product product = productMapper.findById(id);
                if (product == null) {
                    // 缓存穿透：缓存 NULL 值，短过期时间
                    ops.set(cacheKey, PRODUCT_NULL_VALUE, Duration.ofMinutes(5));
                    return null;
                }

                // 4. 正常数据写入缓存
                // 缓存雪崩：在基础 TTL 上增加随机偏移
                long baseSeconds = 3600;
                long randomOffset = random.nextInt(300);
                Duration ttl = Duration.ofSeconds(baseSeconds + randomOffset);
                try {
                    String json = objectMapper.writeValueAsString(product);
                    ops.set(cacheKey, json, ttl);
                } catch (JsonProcessingException ignored) {
                    // 序列化失败则不写缓存，直接返回数据库结果
                }

                return product;
            } finally {
                redisTemplate.delete(lockKey);
            }
        } else {
            // 未获取到锁，稍后重试或直接返回 null，这里简化为短暂睡眠后再查一次缓存
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            cacheValue = ops.get(cacheKey);
            if (cacheValue == null || PRODUCT_NULL_VALUE.equals(cacheValue)) {
                return null;
            }
            try {
                return objectMapper.readValue(cacheValue, Product.class);
            } catch (JsonProcessingException ignored) {
                return null;
            }
        }
    }
}

