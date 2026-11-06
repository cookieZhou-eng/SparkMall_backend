package com.example.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商品评价统计 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewStatsVO {
    /** 平均评分(保留两位小数, 无评价时为 0) */
    private Double avgRating;
    /** 评价总数 */
    private Long total;
}
