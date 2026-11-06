package com.example.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 购物车行展示 VO: 购物车表只存商品ID+数量, 商品信息由商品服务实时填充,
 * 避免商品改价后购物车展示旧价格。
 */
@Data
public class CartItemVO {
    /** 购物车行ID(删除/更新用) */
    private Long id;
    private Long productId;
    private String name;
    private String mainImage;
    /** 当前商品售价 */
    private BigDecimal price;
    /** 购买数量 */
    private Integer count;
    /** 是否勾选: 1-勾选 0-未勾选 */
    private Integer checked;
    /** 当前可用库存(stock - locked) */
    private Integer stock;
    /** 商品是否有效(存在且上架); false 时前端提示"已下架/失效" */
    private Boolean valid;
}
