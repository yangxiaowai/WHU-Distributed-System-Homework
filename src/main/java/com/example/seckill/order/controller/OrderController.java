package com.example.seckill.order.controller;

import com.example.seckill.order.dto.OrderStatusView;
import com.example.seckill.order.dto.SeckillRequest;
import com.example.seckill.order.dto.SeckillSubmitResponse;
import com.example.seckill.order.service.SeckillOrderService;
import com.example.seckill.user.controller.UserController.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final SeckillOrderService seckillOrderService;

    public OrderController(SeckillOrderService seckillOrderService) {
        this.seckillOrderService = seckillOrderService;
    }

    @PostMapping("/seckill")
    public ApiResponse<SeckillSubmitResponse> submitSeckill(@Valid @RequestBody SeckillRequest request) {
        return ApiResponse.success(seckillOrderService.submit(request));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderStatusView> getOrder(@PathVariable("orderId") Long orderId) {
        OrderStatusView view = seckillOrderService.getOrderById(orderId);
        if (view == null) {
            return ApiResponse.fail("订单不存在");
        }
        return ApiResponse.success(view);
    }

    @GetMapping
    public ApiResponse<List<OrderStatusView>> listOrdersByUserId(@RequestParam("userId") Long userId) {
        return ApiResponse.success(seckillOrderService.listOrdersByUserId(userId));
    }
}
