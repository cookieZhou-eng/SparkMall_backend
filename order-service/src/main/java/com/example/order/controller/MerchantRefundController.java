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
 * 商家端退款审核接口(薄控制器): 网关 /order/merchant/** 路由转发过来,
 * 已校验 JWT 且 role=merchant 并透传 X-User-Id/X-User-Role, Service 内再校验一次。
 */
@RestController
@RequestMapping("/order/merchant/refund")
public class MerchantRefundController {

    @Resource
    private RefundService refundService;

    /**
     * 退款申请分页(只包含当前商家订单的退款)
     */
    @GetMapping("/list")
    public Result<Result.PageData<Refund>> list(
            @RequestHeader("X-User-Id") Long merchantId,
            @RequestHeader("X-User-Role") String role,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String refundNo,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(
                refundService.merchantList(merchantId, role, status, refundNo, page, size));
    }

    /**
     * 退款详情(校验退款单属于当前商家的订单)
     */
    @GetMapping("/{id}")
    public Result<Refund> detail(
            @RequestHeader("X-User-Id") Long merchantId,
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id) {
        return Result.success(refundService.merchantDetail(merchantId, role, id));
    }

    /**
     * 审核退款(通过=退款并回补库存 / 拒绝=仅记录备注)
     */
    @PutMapping("/{id}/audit")
    public Result<Void> audit(
            @RequestHeader("X-User-Id") Long merchantId,
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id,
            @RequestBody RefundAuditRequest req) {
        refundService.merchantAudit(merchantId, role, id, req);
        return Result.success();
    }
}
