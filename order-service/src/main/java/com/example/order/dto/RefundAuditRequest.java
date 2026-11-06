package com.example.order.dto;

import lombok.Data;

/**
 * 管理端退款审核请求
 */
@Data
public class RefundAuditRequest {
    /** true-通过并退款, false-拒绝 */
    private Boolean approved;
    /** 审核备注 */
    private String remark;
}
