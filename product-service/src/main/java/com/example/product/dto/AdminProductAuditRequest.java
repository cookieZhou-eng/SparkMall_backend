package com.example.product.dto;

import lombok.Data;

/**
 * 管理端商品审核请求: 审核结论 + 备注(拒绝时必填原因)。
 */
@Data
public class AdminProductAuditRequest {
    /** 审核结论: 1-通过 2-拒绝 */
    private Integer auditStatus;
    /** 审核备注(拒绝原因), 拒绝时必填 */
    private String auditReason;
}
