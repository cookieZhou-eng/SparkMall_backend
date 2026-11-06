package com.example.order.controller;

import com.example.common.entity.Refund;
import com.example.common.result.Result;
import com.example.order.dto.RefundAuditRequest;
import com.example.order.service.RefundService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端退款审核接口。
 * 网关已校验 /admin/** 需要 admin 角色并透传 X-User-Role, Service 内再校验一次(双保险)。
 */
@RestController
@RequestMapping("/admin/refund")
public class AdminRefundController {

    @Resource
    private RefundService refundService;

    /**
     * 退款申请分页
     */
    @GetMapping("/list")
    public Result<Result.PageData<Refund>> list(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String refundNo,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(refundService.adminList(role, status, refundNo, page, size));
    }

    /**
     * 退款详情
     */
    @GetMapping("/{id}")
    public Result<Refund> detail(@RequestHeader(value = "X-User-Role", required = false) String role,
                                 @PathVariable Long id) {
        return Result.success(refundService.adminDetail(role, id));
    }

    /**
     * 审核退款(通过=退款并回补库存 / 拒绝=仅记录备注)
     */
    @PutMapping("/{id}/audit")
    public Result<Void> audit(@RequestHeader(value = "X-User-Role", required = false) String role,
                              @PathVariable Long id,
                              @RequestBody RefundAuditRequest req) {
        refundService.audit(role, id, req);
        return Result.success();
    }
}
