package com.example.order.mq;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 下单成功异步通知消费者(演示): 生产可改为发短信/邮件/站内信。
 */
@Component
public class OrderNotifyConsumer {
    @RabbitListener(queues = "order.notify.queue")
    public void onNotify(String msg) {
        System.out.println("[MQ通知] 收到下单成功消息: " + msg);
    }
}
