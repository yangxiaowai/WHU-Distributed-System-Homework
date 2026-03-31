package com.example.seckill.order.message;

import com.example.seckill.order.service.OrderCreationProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "seckill.order.async-enabled", havingValue = "true", matchIfMissing = true)
public class SeckillOrderConsumer {

    private final OrderCreationProcessor orderCreationProcessor;

    public SeckillOrderConsumer(OrderCreationProcessor orderCreationProcessor) {
        this.orderCreationProcessor = orderCreationProcessor;
    }

    @KafkaListener(topics = "${seckill.order.topic:seckill-order-create}",
            groupId = "${spring.kafka.consumer.group-id:seckill-order-consumer}")
    public void consume(SeckillOrderMessage message) {
        orderCreationProcessor.process(message);
    }
}
