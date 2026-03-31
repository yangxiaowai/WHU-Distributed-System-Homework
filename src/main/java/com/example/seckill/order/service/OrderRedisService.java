package com.example.seckill.order.service;

import com.example.seckill.order.domain.SeckillOrder;
import com.example.seckill.order.dto.OrderStatusView;
import com.example.seckill.order.message.SeckillOrderMessage;
import com.example.seckill.stock.service.StockService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OrderRedisService {

    private static final String ORDER_USER_KEY_PREFIX = "seckill:order:user:";
    private static final String ORDER_STATUS_KEY_PREFIX = "seckill:order:status:";
    private static final String ORDER_USER_INDEX_KEY_PREFIX = "seckill:order:user:index:";

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('exists', KEYS[2]) == 1 then
                return 2
            end
            local stock = redis.call('get', KEYS[1])
            if not stock then
                return 3
            end
            if tonumber(stock) < tonumber(ARGV[2]) then
                return 1
            end
            redis.call('decrby', KEYS[1], ARGV[2])
            redis.call('set', KEYS[2], ARGV[1])
            redis.call('setex', KEYS[3], ARGV[4], ARGV[3])
            redis.call('sadd', KEYS[4], ARGV[1])
            return 0
            """,
            Long.class
    );

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[2]) == ARGV[1] then
                redis.call('del', KEYS[2])
            end
            if redis.call('exists', KEYS[1]) == 1 then
                redis.call('incrby', KEYS[1], ARGV[2])
            end
            redis.call('setex', KEYS[3], ARGV[4], ARGV[3])
            redis.call('srem', KEYS[4], ARGV[1])
            return 1
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final StockService stockService;
    private final Duration statusTtl;

    public OrderRedisService(StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper,
                             StockService stockService,
                             @Value("${seckill.order.status-ttl-hours:24}") long statusTtlHours) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.stockService = stockService;
        this.statusTtl = Duration.ofHours(statusTtlHours);
    }

    public ReservationResult reserve(SeckillOrderMessage message) {
        OrderStatusView pendingStatus = buildPendingStatus(message);
        String statusJson = toJson(pendingStatus);
        if (statusJson == null) {
            return ReservationResult.infrastructureError();
        }

        try {
            Long result = redisTemplate.execute(
                    RESERVE_SCRIPT,
                    List.of(
                            stockService.buildStockCacheKey(message.getProductId()),
                            buildUserOrderKey(message.getUserId(), message.getProductId()),
                            buildOrderStatusKey(message.getOrderId()),
                            buildUserOrderIndexKey(message.getUserId())
                    ),
                    String.valueOf(message.getOrderId()),
                    String.valueOf(message.getAmount()),
                    statusJson,
                    String.valueOf(statusTtl.toSeconds())
            );

            if (result == null) {
                return ReservationResult.infrastructureError();
            }
            if (result == 0L) {
                return ReservationResult.success();
            }
            if (result == 1L) {
                return ReservationResult.outOfStock();
            }
            if (result == 2L) {
                return ReservationResult.duplicate(getExistingOrderId(message.getUserId(), message.getProductId()));
            }
            if (result == 3L) {
                return ReservationResult.stockNotLoaded();
            }
            return ReservationResult.infrastructureError();
        } catch (DataAccessException ignored) {
            return ReservationResult.infrastructureError();
        }
    }

    public void rollbackReservation(SeckillOrderMessage message, String status, String messageText) {
        OrderStatusView failedStatus = buildStatus(message, status, messageText, "REDIS");
        String statusJson = toJson(failedStatus);
        if (statusJson == null) {
            return;
        }
        try {
            redisTemplate.execute(
                    ROLLBACK_SCRIPT,
                    List.of(
                            stockService.buildStockCacheKey(message.getProductId()),
                            buildUserOrderKey(message.getUserId(), message.getProductId()),
                            buildOrderStatusKey(message.getOrderId()),
                            buildUserOrderIndexKey(message.getUserId())
                    ),
                    String.valueOf(message.getOrderId()),
                    String.valueOf(message.getAmount()),
                    statusJson,
                    String.valueOf(statusTtl.toSeconds())
            );
        } catch (DataAccessException ignored) {
        }
    }

    public void markOrderSuccess(SeckillOrder order) {
        OrderStatusView successStatus = new OrderStatusView();
        successStatus.setOrderId(order.getId());
        successStatus.setOrderNo(order.getOrderNo());
        successStatus.setUserId(order.getUserId());
        successStatus.setProductId(order.getProductId());
        successStatus.setAmount(order.getAmount());
        successStatus.setStatus("CREATED");
        successStatus.setMessage("订单创建成功");
        successStatus.setCreateTime(order.getCreateTime());
        successStatus.setUpdateTime(order.getUpdateTime());
        successStatus.setSource("DB");
        writeStatus(successStatus);
        bindExistingOrder(order.getUserId(), order.getProductId(), order.getId());
    }

    public void bindExistingOrder(Long userId, Long productId, Long orderId) {
        try {
            redisTemplate.opsForValue().set(buildUserOrderKey(userId, productId), String.valueOf(orderId));
            redisTemplate.opsForSet().add(buildUserOrderIndexKey(userId), String.valueOf(orderId));
        } catch (DataAccessException ignored) {
        }
    }

    public OrderStatusView getOrderStatus(Long orderId) {
        try {
            String payload = redisTemplate.opsForValue().get(buildOrderStatusKey(orderId));
            if (payload == null) {
                return null;
            }
            return objectMapper.readValue(payload, OrderStatusView.class);
        } catch (DataAccessException | JsonProcessingException ignored) {
            return null;
        }
    }

    public Set<Long> getUserOrderIds(Long userId) {
        try {
            Set<String> values = redisTemplate.opsForSet().members(buildUserOrderIndexKey(userId));
            if (values == null) {
                return Set.of();
            }
            return values.stream()
                    .map(this::safeParseLong)
                    .filter(id -> id != null)
                    .collect(Collectors.toSet());
        } catch (DataAccessException ignored) {
            return Set.of();
        }
    }

    public Long getExistingOrderId(Long userId, Long productId) {
        try {
            String value = redisTemplate.opsForValue().get(buildUserOrderKey(userId, productId));
            return safeParseLong(value);
        } catch (DataAccessException ignored) {
            return null;
        }
    }

    private OrderStatusView buildPendingStatus(SeckillOrderMessage message) {
        return buildStatus(message, "PENDING", "秒杀请求已受理，订单正在异步创建", "REDIS");
    }

    private OrderStatusView buildStatus(SeckillOrderMessage message, String status, String messageText, String source) {
        OrderStatusView statusView = new OrderStatusView();
        statusView.setOrderId(message.getOrderId());
        statusView.setOrderNo(message.getOrderNo());
        statusView.setUserId(message.getUserId());
        statusView.setProductId(message.getProductId());
        statusView.setAmount(message.getAmount());
        statusView.setStatus(status);
        statusView.setMessage(messageText);
        statusView.setCreateTime(message.getCreateTime());
        statusView.setUpdateTime(message.getCreateTime());
        statusView.setSource(source);
        return statusView;
    }

    private void writeStatus(OrderStatusView statusView) {
        String json = toJson(statusView);
        if (json == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(buildOrderStatusKey(statusView.getOrderId()), json, statusTtl);
        } catch (DataAccessException ignored) {
        }
    }

    private String toJson(OrderStatusView statusView) {
        try {
            return objectMapper.writeValueAsString(statusView);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String buildUserOrderKey(Long userId, Long productId) {
        return ORDER_USER_KEY_PREFIX + userId + ":" + productId;
    }

    private String buildOrderStatusKey(Long orderId) {
        return ORDER_STATUS_KEY_PREFIX + orderId;
    }

    private String buildUserOrderIndexKey(Long userId) {
        return ORDER_USER_INDEX_KEY_PREFIX + userId;
    }

    private Long safeParseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record ReservationResult(ReservationStatus status, Long existingOrderId) {

        public static ReservationResult success() {
            return new ReservationResult(ReservationStatus.SUCCESS, null);
        }

        public static ReservationResult outOfStock() {
            return new ReservationResult(ReservationStatus.OUT_OF_STOCK, null);
        }

        public static ReservationResult duplicate(Long existingOrderId) {
            return new ReservationResult(ReservationStatus.DUPLICATE, existingOrderId);
        }

        public static ReservationResult stockNotLoaded() {
            return new ReservationResult(ReservationStatus.STOCK_NOT_LOADED, null);
        }

        public static ReservationResult infrastructureError() {
            return new ReservationResult(ReservationStatus.INFRASTRUCTURE_ERROR, null);
        }
    }

    public enum ReservationStatus {
        SUCCESS,
        OUT_OF_STOCK,
        DUPLICATE,
        STOCK_NOT_LOADED,
        INFRASTRUCTURE_ERROR
    }
}
