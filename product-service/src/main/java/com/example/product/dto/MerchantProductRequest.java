package com.example.product.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商家新增/编辑商品请求 DTO
 * 新增时使用 name/categoryId/price/stock 等全部字段; 编辑时携带 id, stock 可选(传入则修改库存)
 */
@Data
public class MerchantProductRequest {
    /** 商品 id(编辑时必传, 新增时忽略) */
    private Long id;
    private String name;            // 商品名称
    private String subtitle;        // 副标题
    private Long categoryId;        // 分类 id(必须存在)
    private BigDecimal price;       // 销售价(>0)
    private BigDecimal originalPrice; // 原价(划线展示, 可空)
    private String mainImage;       // 主图URL
    private String detailImages;    // 详情图URL列表, 逗号分隔
    /** 库存: 新增时必传(>=0); 编辑时可选, 传入且 >=0 则覆盖可用库存(不传不修改) */
    private Integer stock;
}
