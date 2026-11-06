package com.example.order.dto;

import lombok.Data;

import java.util.List;

/**
 * 用户申请退款请求
 */
@Data
public class RefundApplyRequest {
    /** 订单ID */
    private Long orderId;
    /** 退款类型: 1-仅退款 2-退货退款 */
    private Integer type;
    /** 退款原因 */
    private String reason;
    /** 问题描述, 最多 500 字 */
    private String description;
    /** 凭证图片 URL 列表, 最多 3 张 */
    private List<String> images;
}
