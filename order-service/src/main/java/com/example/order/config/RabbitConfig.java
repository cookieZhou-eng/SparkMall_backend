package com.example.order.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 队列/交换器声明。
 * 延迟消息实现订单超时: 消息先进"延迟队列"(TTL= expireMinutes 后过期)
 * -> 过期未消费自动转入"死信队列" -> 消费者真正执行取消。
 */
@Configuration
@EnableRabbit   // 开启 @RabbitListener 注解监听
public class RabbitConfig {

    public static final String ORDER_EXPIRE_EXCHANGE = "order.expire.exchange";
    public static final String ORDER_NOTIFY_EXCHANGE = "order.notify.exchange";

    @Value("${app.order.expire-minutes:30}")
    private long expireMinutes;

    /** ===== ① 订单超时延迟取消 ===== */
    @Bean
    public DirectExchange orderExpireExchange() {
        return new DirectExchange(ORDER_EXPIRE_EXCHANGE);
    }

    /** 延迟队列: 消息在此排 TTL, 到期未被消费则转死信 */
    @Bean
    public Queue orderExpireDelayQueue() {
        int ttlMs = (int) (expireMinutes * 60 * 1000);
        return QueueBuilder.durable("order.expire.delay.queue")
                .ttl(ttlMs)                                    // 做 x-message-ttl
                .deadLetterExchange(ORDER_EXPIRE_EXCHANGE)      // 死亡后投递此交换器
                .deadLetterRoutingKey("order.expire.cancel")    // 死亡后 routingKey
                .build();
    }

    /** 死信队列: 真正被消费者监听, 处理"到期订单" */
    @Bean
    public Queue orderExpireDeadQueue() {
        return QueueBuilder.durable("order.expire.dead.queue").build();
    }

    @Bean
    public Binding orderExpireDelayBinding() {
        return BindingBuilder.bind(orderExpireDelayQueue())
                .to(orderExpireExchange()).with("order.expire.delay");
    }

    @Bean
    public Binding orderExpireDeadBinding() {
        return BindingBuilder.bind(orderExpireDeadQueue())
                .to(orderExpireExchange()).with("order.expire.cancel");
    }

    /** ===== ② 下单成功异步通知(点对点) ===== */
    @Bean
    public DirectExchange orderNotifyExchange() {
        return new DirectExchange(ORDER_NOTIFY_EXCHANGE);
    }

    @Bean
    public Queue orderNotifyQueue() {
        return QueueBuilder.durable("order.notify.queue").build();
    }

    @Bean
    public Binding orderNotifyBinding() {
        return BindingBuilder.bind(orderNotifyQueue())
                .to(orderNotifyExchange()).with("order.created");
    }
}
