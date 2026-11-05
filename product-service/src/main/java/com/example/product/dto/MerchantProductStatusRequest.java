package com.example.product.dto;

import lombok.Data;

/**
 * 商家上下架商品请求 DTO
 */
@Data
public class MerchantProductStatusRequest {
    /** 上架状态: 0-下架 1-上架 */
    private Integer status;
}
