package com.example.product.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 拦截器配置
 *
 * 必须配置 PaginationInnerInterceptor 才能让 BaseMapper 的 selectPage 真正按分页执行,
 * 否则 selectPage 会一次性返回全部数据(无 LIMIT)
 *
 * OptimisticLockerInnerInterceptor 用于 @Version 字段:
 *  执行 updateById 时, MP 自动在 SQL 后追加 WHERE version = 旧值,
 *  并把新 version 设为旧值+1, 实现"先读后写"的乐观锁
 */
@Configuration
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 1. 分页拦截器(指定 MySQL 方言)
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        // 2. 乐观锁拦截器(配合 @Version 注解使用)
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
