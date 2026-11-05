package com.example.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀订单确认请求(Feign 调用 product-service 使用)
 */
@Data
@AllArgsConstructor   // 加上这个, 支持 new SeckillConfirmRequest(...) 带参构造
@NoArgsConstructor   // 加上这个, 支持 new SeckillConfirmRequest() 无参构造
public class SeckillConfirmRequest {
    private Long userId;      // 抢购用户
    private Long productId;   // 秒杀商品
    private Integer count;    // 数量(秒杀限购)
}

