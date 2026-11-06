package com.example.order.dto;

import lombok.Data;

/**
 * 加入购物车请求
 */
@Data
public class CartAddRequest {
    private Long productId;
    /** 本次加购数量(默认 1) */
    private Integer count;
}
