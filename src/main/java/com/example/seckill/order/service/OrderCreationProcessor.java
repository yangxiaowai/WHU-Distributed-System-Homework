package com.example.seckill.order.service;

import com.example.seckill.order.domain.SeckillOrder;
import com.example.seckill.order.dto.OrderStatusView;
import com.example.seckill.order.mapper.SeckillOrderMapper;
import com.example.seckill.order.message.SeckillOrderMessage;
import com.example.seckill.stock.mapper.StockMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderCreationProcessor {

    private final TransactionTemplate transactionTemplate;
    private final SeckillOrderMapper seckillOrderMapper;
    private final StockMapper stockMapper;
    private final OrderRedisService orderRedisService;

    public OrderCreationProcessor(TransactionTemplate transactionTemplate,
                                  SeckillOrderMapper seckillOrderMapper,
                                  StockMapper stockMapper,
                                  OrderRedisService orderRedisService) {
        this.transactionTemplate = transactionTemplate;
        this.seckillOrderMapper = seckillOrderMapper;
        this.stockMapper = stockMapper;
        this.orderRedisService = orderRedisService;
    }

    public OrderStatusView process(SeckillOrderMessage message) {
        try {
            SeckillOrder order = transactionTemplate.execute(status -> createOrder(message));
            if (order == null) {
                throw new IllegalStateException("订单创建失败");
            }
            orderRedisService.markOrderSuccess(order);
            return toView(order, "DB");
        } catch (DuplicateOrderException ex) {
            orderRedisService.rollbackReservation(message, "FAILED", "同一用户同一商品只能秒杀一次");
            if (ex.getExistingOrderId() != null) {
                SeckillOrder existingOrder = seckillOrderMapper.findById(ex.getExistingOrderId());
                if (existingOrder != null) {
                    orderRedisService.bindExistingOrder(existingOrder.getUserId(), existingOrder.getProductId(), existingOrder.getId());
                    orderRedisService.markOrderSuccess(existingOrder);
                    return toView(existingOrder, "DB");
                }
            }
            return orderRedisService.getOrderStatus(message.getOrderId());
        } catch (OutOfStockException ex) {
            orderRedisService.rollbackReservation(message, "FAILED", "库存不足");
            return orderRedisService.getOrderStatus(message.getOrderId());
        } catch (DuplicateKeyException ex) {
            orderRedisService.rollbackReservation(message, "FAILED", "同一用户同一商品只能秒杀一次");
            SeckillOrder existingOrder = seckillOrderMapper.findByUserIdAndProductId(message.getUserId(), message.getProductId());
            if (existingOrder != null) {
                orderRedisService.bindExistingOrder(existingOrder.getUserId(), existingOrder.getProductId(), existingOrder.getId());
                orderRedisService.markOrderSuccess(existingOrder);
                return toView(existingOrder, "DB");
            }
            return orderRedisService.getOrderStatus(message.getOrderId());
        } catch (Exception ex) {
            orderRedisService.rollbackReservation(message, "FAILED", "订单创建失败，请稍后重试");
            return orderRedisService.getOrderStatus(message.getOrderId());
        }
    }

    private SeckillOrder createOrder(SeckillOrderMessage message) {
        SeckillOrder existingOrderById = seckillOrderMapper.findById(message.getOrderId());
        if (existingOrderById != null) {
            return existingOrderById;
        }

        SeckillOrder existingOrder = seckillOrderMapper.findByUserIdAndProductId(message.getUserId(), message.getProductId());
        if (existingOrder != null) {
            throw new DuplicateOrderException(existingOrder.getId());
        }

        int affectedRows = stockMapper.deductAvailable(message.getProductId(), message.getAmount());
        if (affectedRows == 0) {
            throw new OutOfStockException();
        }

        SeckillOrder order = new SeckillOrder();
        order.setId(message.getOrderId());
        order.setOrderNo(message.getOrderNo());
        order.setUserId(message.getUserId());
        order.setProductId(message.getProductId());
        order.setAmount(message.getAmount());
        order.setStatus(0);
        seckillOrderMapper.insert(order);
        return seckillOrderMapper.findById(order.getId());
    }

    private OrderStatusView toView(SeckillOrder order, String source) {
        OrderStatusView view = new OrderStatusView();
        view.setOrderId(order.getId());
        view.setOrderNo(order.getOrderNo());
        view.setUserId(order.getUserId());
        view.setProductId(order.getProductId());
        view.setAmount(order.getAmount());
        view.setStatus(mapDbStatus(order.getStatus()));
        view.setMessage("订单创建成功");
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

    private static class DuplicateOrderException extends RuntimeException {

        private final Long existingOrderId;

        private DuplicateOrderException(Long existingOrderId) {
            this.existingOrderId = existingOrderId;
        }

        public Long getExistingOrderId() {
            return existingOrderId;
        }
    }

    private static class OutOfStockException extends RuntimeException {
    }
}
