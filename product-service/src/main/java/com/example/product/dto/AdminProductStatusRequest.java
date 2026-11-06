package com.example.product.dto;

import lombok.Data;

/**
 * 管理端商品上下架请求
 */
@Data
public class AdminProductStatusRequest {
    /** 1-上架 0-下架 */
    private Integer status;
}
