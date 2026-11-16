package com.example.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 商品服务启动类
 * @MapperScan 扫描 mapper 包下所有接口, 自动注册为 Spring Bean
 * @EnableFeignClients 启用 Feign 客户端(管理端列表调用 user-service 回填商家店铺名)
 */
@SpringBootApplication
@EnableScheduling   // 开启定时任务(秒杀库存对账, 校准 Redis 与 DB 的库存差值)
@EnableFeignClients(basePackages = "com.example.product.feign")
@MapperScan("com.example.product.mapper")
public class ProductApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
