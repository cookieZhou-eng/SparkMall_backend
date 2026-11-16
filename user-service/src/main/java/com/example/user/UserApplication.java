package com.example.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 用户服务启动类
 */
@SpringBootApplication
//@MapperScan()注解：
@MapperScan("com.example.user.mapper")
@EnableFeignClients(basePackages = "com.example.user.feign")
public class UserApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
