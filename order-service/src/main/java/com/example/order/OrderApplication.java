package com.example.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients // 开启Feign远程调用
@EnableScheduling   // 开启定时任务(用于订单超时未支付自动释放锁定的库存)
@MapperScan("com.example.order.mapper") // 扫描 Mapper 接口, 让 MyBatis-Plus 生成代理实现
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}