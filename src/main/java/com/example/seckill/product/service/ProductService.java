package com.example.seckill.product.service;

import com.example.seckill.config.datasource.ReadOnly;
import com.example.seckill.product.domain.Product;
import com.example.seckill.product.mapper.ProductMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ProductService {

    private static final String PRODUCT_CACHE_KEY_PREFIX = "product:detail:";
    private static final String PRODUCT_CACHE_LOCK_KEY_PREFIX = "product:detail:lock:";
    private static final String PRODUCT_NULL_VALUE = "NULL";
    private static final Duration PRODUCT_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration NULL_CACHE_TTL = Duration.ofMinutes(2);
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final long PRODUCT_CACHE_JITTER_SECONDS = 300;
    private static final long NULL_CACHE_JITTER_SECONDS = 30;
    private static final int CACHE_RETRY_TIMES = 5;
    private static final long CACHE_RETRY_SLEEP_MILLIS = 40;
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final ProductMapper productMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ProductService(ProductMapper productMapper,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @ReadOnly
    public Product getProductById(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        String cacheKey = buildProductCacheKey(id);
        CacheLookupResult cacheLookup = readFromCache(cacheKey);
        if (cacheLookup.present()) {
            return cacheLookup.product();
        }

        String lockKey = buildLockKey(id);
        String lockValue = UUID.randomUUID().toString();

        if (tryAcquireLock(lockKey, lockValue)) {
            try {
                CacheLookupResult secondLookup = readFromCache(cacheKey);
                if (secondLookup.present()) {
                    return secondLookup.product();
                }
                return loadFromDatabaseAndCache(id, cacheKey);
            } finally {
                releaseLock(lockKey, lockValue);
            }
        }

        return waitForCacheRebuild(cacheKey, id);
    }

    @ReadOnly
    public List<Product> listAllProducts() {
        return productMapper.listAll();
    }

    public Product updateProduct(Product product) {
        int affectedRows = productMapper.updateById(product);
        if (affectedRows == 0) {
            throw new IllegalArgumentException("商品不存在");
        }
        evictProductCache(product.getId());
        return productMapper.findById(product.getId());
    }

    public void evictProductCache(Long id) {
        if (id == null || id <= 0) {
            return;
        }
        deleteCache(buildProductCacheKey(id));
    }

    private Product waitForCacheRebuild(String cacheKey, Long id) {
        for (int attempt = 0; attempt < CACHE_RETRY_TIMES; attempt++) {
            sleepQuietly(CACHE_RETRY_SLEEP_MILLIS * (attempt + 1));
            CacheLookupResult cacheLookup = readFromCache(cacheKey);
            if (cacheLookup.present()) {
                return cacheLookup.product();
            }
        }
        return loadFromDatabaseAndCache(id, cacheKey);
    }

    private Product loadFromDatabaseAndCache(Long id, String cacheKey) {
        Product product = productMapper.findById(id);
        if (product == null) {
            cacheNullValue(cacheKey);
            return null;
        }
        cacheProduct(cacheKey, product);
        return product;
    }

    private CacheLookupResult readFromCache(String cacheKey) {
        String cacheValue = getCacheValue(cacheKey);
        if (cacheValue == null) {
            return CacheLookupResult.miss();
        }
        if (PRODUCT_NULL_VALUE.equals(cacheValue)) {
            return CacheLookupResult.nullValue();
        }
        try {
            return CacheLookupResult.hit(objectMapper.readValue(cacheValue, Product.class));
        } catch (JsonProcessingException ignored) {
            deleteCache(cacheKey);
            return CacheLookupResult.miss();
        }
    }

    private String getCacheValue(String cacheKey) {
        try {
            ValueOperations<String, String> ops = redisTemplate.opsForValue();
            return ops.get(cacheKey);
        } catch (DataAccessException ignored) {
            return null;
        }
    }

    private void cacheProduct(String cacheKey, Product product) {
        try {
            String json = objectMapper.writeValueAsString(product);
            Duration ttl = PRODUCT_CACHE_TTL.plusSeconds(randomJitter(PRODUCT_CACHE_JITTER_SECONDS));
            redisTemplate.opsForValue().set(cacheKey, json, ttl);
        } catch (JsonProcessingException | DataAccessException ignored) {
        }
    }

    private void cacheNullValue(String cacheKey) {
        try {
            Duration ttl = NULL_CACHE_TTL.plusSeconds(randomJitter(NULL_CACHE_JITTER_SECONDS));
            redisTemplate.opsForValue().set(cacheKey, PRODUCT_NULL_VALUE, ttl);
        } catch (DataAccessException ignored) {
        }
    }

    private boolean tryAcquireLock(String lockKey, String lockValue) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, LOCK_TTL)
            );
        } catch (DataAccessException ignored) {
            return false;
        }
    }

    private void releaseLock(String lockKey, String lockValue) {
        try {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
        } catch (DataAccessException ignored) {
        }
    }

    private void deleteCache(String cacheKey) {
        try {
            redisTemplate.delete(cacheKey);
        } catch (DataAccessException ignored) {
        }
    }

    private long randomJitter(long boundSeconds) {
        return ThreadLocalRandom.current().nextLong(boundSeconds + 1);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildProductCacheKey(Long id) {
        return PRODUCT_CACHE_KEY_PREFIX + id;
    }

    private String buildLockKey(Long id) {
        return PRODUCT_CACHE_LOCK_KEY_PREFIX + id;
    }

    private record CacheLookupResult(boolean present, Product product) {
        private static CacheLookupResult miss() {
            return new CacheLookupResult(false, null);
        }

        private static CacheLookupResult hit(Product product) {
            return new CacheLookupResult(true, product);
        }

        private static CacheLookupResult nullValue() {
            return new CacheLookupResult(true, null);
        }
    }
}
