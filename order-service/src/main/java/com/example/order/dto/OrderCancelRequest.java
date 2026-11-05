package com.example.order.dto;

import lombok.Data;

/**
 * 取消订单请求 DTO
 */
@Data
public class OrderCancelRequest {
    private String orderNo;   // 订单号
    private Long userId;      // 当前操作用户
}