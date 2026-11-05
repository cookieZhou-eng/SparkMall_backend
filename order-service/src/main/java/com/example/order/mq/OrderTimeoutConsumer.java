package com.example.order.mq;

import com.example.order.service.OrderService;
import jakarta.annotation.Resource;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单超时取消消费者: 消费死信队列, 到期未支付订单触发取消并释放锁定库存。
 */
@Component
public class OrderTimeoutConsumer {
    @Resource
    private OrderService orderService;

    @RabbitListener(queues = "order.expire.dead.queue")
    public void onExpire(String orderNo) {
        orderService.cancelByTimeout(orderNo);
    }
}

