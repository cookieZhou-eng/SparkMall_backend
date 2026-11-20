package com.example.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableDiscoveryClient // 注册到 Nacos, 供网关路由与 Feign 发现
@EnableFeignClients    // 开启 Feign 远程调用(工具执行时调用 product/order 现有接口)
@MapperScan("com.example.ai.mapper") // 扫描 Mapper 接口, 让 MyBatis-Plus 生成代理实现
public class AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
