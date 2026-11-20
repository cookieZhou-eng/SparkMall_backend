package com.example.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 退款申请草稿建议 VO(AI 助手 applyRefundDraft 工具用, 仅返回建议不落库):
 * 前端/助手按此引导用户到订单页正式提交申请。
 */
@Data
public class RefundDraftVO {
    private Long orderId;
    private String orderNo;
    /** 建议退款金额(= 订单实付总额) */
    private BigDecimal amount;
    /** 建议退款类型: 1-仅退款(未发货) 2-退货退款(已发货/已完成) */
    private Integer suggestedType;
}
