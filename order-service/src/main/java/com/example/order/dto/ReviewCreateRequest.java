package com.example.order.dto;

import lombok.Data;

import java.util.List;

/**
 * 订单评价请求
 */
@Data
public class ReviewCreateRequest {
    /** 被评价的订单明细ID(来自订单详情) */
    private Long orderItemId;
    /** 评分 1-5 */
    private Integer rating;
    /** 评价内容, 最多 500 字 */
    private String content;
    /** 评价图片 URL 列表, 最多 3 张 */
    private List<String> images;
    /** 是否匿名 */
    private Boolean anonymous;
}
