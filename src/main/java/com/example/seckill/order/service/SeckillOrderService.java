package com.example.seckill.order.service;

import com.example.seckill.common.id.SnowflakeIdGenerator;
import com.example.seckill.order.domain.SeckillOrder;
import com.example.seckill.order.dto.OrderStatusView;
import com.example.seckill.order.dto.SeckillRequest;
import com.example.seckill.order.dto.SeckillSubmitResponse;
import com.example.seckill.order.mapper.SeckillOrderMapper;
import com.example.seckill.order.message.SeckillOrderMessage;
import com.example.seckill.product.domain.Product;
import com.example.seckill.product.service.ProductService;
import com.example.seckill.stock.service.StockService;
import com.example.seckill.user.domain.User;
import com.example.seckill.user.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class SeckillOrderService {

    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final UserMapper userMapper;
    private final ProductService productService;
    private final StockService stockService;
    private final OrderRedisService orderRedisService;
    private final OrderCreationProcessor orderCreationProcessor;
    private final SeckillOrderMapper seckillOrderMapper;
    private final KafkaTemplate<String, SeckillOrderMessage> kafkaTemplate;
    private final boolean asyncEnabled;
    private final String topic;

    public SeckillOrderService(SnowflakeIdGenerator snowflakeIdGenerator,
                               UserMapper userMapper,
                               ProductService productService,
                               StockService stockService,
                               OrderRedisService orderRedisService,
                               OrderCreationProcessor orderCreationProcessor,
                               SeckillOrderMapper seckillOrderMapper,
                               KafkaTemplate<String, SeckillOrderMessage> kafkaTemplate,
                               @Value("${seckill.order.async-enabled:true}") boolean asyncEnabled,
                               @Value("${seckill.order.topic:seckill-order-create}") String topic) {
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.userMapper = userMapper;
        this.productService = productService;
        this.stockService = stockService;
        this.orderRedisService = orderRedisService;
        this.orderCreationProcessor = orderCreationProcessor;
        this.seckillOrderMapper = seckillOrderMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.asyncEnabled = asyncEnabled;
        this.topic = topic;
    }

    public SeckillSubmitResponse submit(SeckillRequest request) {
        validateRequest(request);

        SeckillOrderMessage orderMessage = buildOrderMessage(request);
        OrderRedisService.ReservationResult reservation = reserveWithRetry(orderMessage);

        return switch (reservation.status()) {
            case SUCCESS -> dispatch(orderMessage);
            case DUPLICATE -> buildDuplicateResponse(request, reservation.existingOrderId());
            case OUT_OF_STOCK -> throw new IllegalArgumentException("库存不足");
            case STOCK_NOT_LOADED -> throw new IllegalStateException("库存缓存未初始化");
            case INFRASTRUCTURE_ERROR -> throw new IllegalStateException("秒杀服务暂不可用，请稍后重试");
        };
    }

    public OrderStatusView getOrderById(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return null;
        }
        SeckillOrder order = seckillOrderMapper.findById(orderId);
        if (order != null) {
            return toView(order, "DB");
        }
        return orderRedisService.getOrderStatus(orderId);
    }

    public List<OrderStatusView> listOrdersByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            return List.of();
        }

        List<OrderStatusView> result = new ArrayList<>();
        List<SeckillOrder> dbOrders = seckillOrderMapper.findByUserId(userId);
        Set<Long> existingOrderIds = new HashSet<>();
        for (SeckillOrder order : dbOrders) {
            result.add(toView(order, "DB"));
            existingOrderIds.add(order.getId());
        }

        for (Long orderId : orderRedisService.getUserOrderIds(userId)) {
            if (existingOrderIds.contains(orderId)) {
                continue;
            }
            OrderStatusView cachedStatus = orderRedisService.getOrderStatus(orderId);
            if (cachedStatus != null) {
                result.add(cachedStatus);
            }
        }

        result.sort(Comparator
                .comparing(SeckillOrderService::resolveOrderTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(OrderStatusView::getOrderId, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    private void validateRequest(SeckillRequest request) {
        User user = userMapper.findById(request.getUserId());
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        Product product = productService.getProductById(request.getProductId());
        if (product == null) {
            throw new IllegalArgumentException("商品不存在");
        }
        if (!Integer.valueOf(1).equals(product.getStatus())) {
            throw new IllegalArgumentException("商品未上架");
        }
        if (stockService.getStockByProductId(request.getProductId()) == null) {
            throw new IllegalArgumentException("商品库存不存在");
        }
    }

    private SeckillOrderMessage buildOrderMessage(SeckillRequest request) {
        long orderId = snowflakeIdGenerator.nextId();
        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setOrderId(orderId);
        message.setOrderNo(String.valueOf(orderId));
        message.setUserId(request.getUserId());
        message.setProductId(request.getProductId());
        message.setAmount(request.getAmount());
        message.setCreateTime(LocalDateTime.now());
        return message;
    }

    private OrderRedisService.ReservationResult reserveWithRetry(SeckillOrderMessage orderMessage) {
        OrderRedisService.ReservationResult result = orderRedisService.reserve(orderMessage);
        if (result.status() == OrderRedisService.ReservationStatus.STOCK_NOT_LOADED) {
            stockService.syncStockCache(orderMessage.getProductId());
            result = orderRedisService.reserve(orderMessage);
        }
        return result;
    }

    private SeckillSubmitResponse dispatch(SeckillOrderMessage orderMessage) {
        if (!asyncEnabled) {
            OrderStatusView statusView = orderCreationProcessor.process(orderMessage);
            return new SeckillSubmitResponse(
                    statusView == null ? orderMessage.getOrderId() : statusView.getOrderId(),
                    statusView == null ? orderMessage.getOrderNo() : statusView.getOrderNo(),
                    statusView == null ? "UNKNOWN" : statusView.getStatus(),
                    statusView == null ? "订单处理结束" : statusView.getMessage()
            );
        }

        try {
            kafkaTemplate.send(topic, String.valueOf(orderMessage.getProductId()), orderMessage).get(5, TimeUnit.SECONDS);
            return new SeckillSubmitResponse(
                    orderMessage.getOrderId(),
                    orderMessage.getOrderNo(),
                    "PENDING",
                    "秒杀请求已受理，订单正在异步创建"
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new SeckillSubmitResponse(
                    orderMessage.getOrderId(),
                    orderMessage.getOrderNo(),
                    "PENDING",
                    "消息投递结果未知，请稍后通过订单号查询"
            );
        } catch (TimeoutException ex) {
            return new SeckillSubmitResponse(
                    orderMessage.getOrderId(),
                    orderMessage.getOrderNo(),
                    "PENDING",
                    "消息投递超时，请稍后通过订单号查询"
            );
        } catch (ExecutionException ex) {
            orderRedisService.rollbackReservation(orderMessage, "FAILED", "消息投递失败");
            throw new IllegalStateException("系统繁忙，请稍后重试");
        }
    }

    private SeckillSubmitResponse buildDuplicateResponse(SeckillRequest request, Long existingOrderId) {
        if (existingOrderId != null) {
            OrderStatusView existingOrder = getOrderById(existingOrderId);
            if (existingOrder != null) {
                return new SeckillSubmitResponse(
                        existingOrder.getOrderId(),
                        existingOrder.getOrderNo(),
                        existingOrder.getStatus(),
                        "重复请求已拦截，返回已有订单状态"
                );
            }
        }

        SeckillOrder existingOrder = seckillOrderMapper.findByUserIdAndProductId(request.getUserId(), request.getProductId());
        if (existingOrder != null) {
            return new SeckillSubmitResponse(
                    existingOrder.getId(),
                    existingOrder.getOrderNo(),
                    mapDbStatus(existingOrder.getStatus()),
                    "重复请求已拦截，返回已有订单状态"
            );
        }

        throw new IllegalArgumentException("同一用户同一商品只能秒杀一次");
    }

    private OrderStatusView toView(SeckillOrder order, String source) {
        OrderStatusView view = new OrderStatusView();
        view.setOrderId(order.getId());
        view.setOrderNo(order.getOrderNo());
        view.setUserId(order.getUserId());
        view.setProductId(order.getProductId());
        view.setAmount(order.getAmount());
        view.setStatus(mapDbStatus(order.getStatus()));
        view.setMessage("订单已创建");
        view.setCreateTime(order.getCreateTime());
        view.setUpdateTime(order.getUpdateTime());
        view.setSource(source);
        return view;
    }

    private String mapDbStatus(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case 0 -> "CREATED";
            case 1 -> "PAID";
            case 2 -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }

    private static LocalDateTime resolveOrderTime(OrderStatusView view) {
        return view.getUpdateTime() == null ? view.getCreateTime() : view.getUpdateTime();
    }
}
