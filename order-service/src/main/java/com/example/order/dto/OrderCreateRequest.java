package com.example.order.dto;

import lombok.Data;

import java.util.List;

/**
 * 下单请求 DTO
 * 前端购物车结算时一次性提交: 收货信息 + 多个商品条目
 */
@Data
public class OrderCreateRequest {
    private Long userId;          // 下单用户ID
    private String receiverName;  // 收货人
    private String receiverPhone; // 收货电话
    private String receiverAddr;  // 收货地址
    private String remark;        // 订单备注(可选)

    /** 购买的商品条目 */
    private List<Item> items;

    @Data
    public static class Item {
        private Long productId;   // 商品ID
        private Integer count;    // 购买数量
    }
}
