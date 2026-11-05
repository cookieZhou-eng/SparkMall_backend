package com.example.order.dto;

import lombok.Data;

/**
 * 支付请求 DTO
 * 前端在"模拟支付弹窗"里选择支付方式后调用 /order/pay 时提交
 */
@Data
public class OrderPayRequest {
    private Long orderId;     // 订单ID(可选, 与 orderNo 二选一即可)
    private String orderNo;   // 订单号(业务主键, 推荐用它定位订单)
    private Long userId;      // 支付人ID(校验订单归属)
    private String channel;   // 支付渠道: alipay-支付宝 wechat-微信 unionpay-银联 mock-模拟支付
}