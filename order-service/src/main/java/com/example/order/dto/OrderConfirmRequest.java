package com.example.order.dto;

import lombok.Data;

/**
 * 确认收货请求 DTO
 * userId 不从 body 取, 由网关透传的请求头 X-User-Id 提供。
 */
@Data
public class OrderConfirmRequest {
    private String orderNo; // 订单号
}
