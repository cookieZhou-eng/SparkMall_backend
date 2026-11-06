package com.example.order.controller;

import com.example.common.entity.Refund;
import com.example.common.result.Result;
import com.example.order.dto.RefundApplyRequest;
import com.example.order.service.RefundService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户侧退款售后接口
 */
@RestController
@RequestMapping("/refund")
public class RefundController {

    @Resource
    private RefundService refundService;

    /**
     * 申请退款
     */
    @PostMapping("/apply")
    public Result<Refund> apply(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                @RequestBody RefundApplyRequest req) {
        return Result.success(refundService.apply(userId, req));
    }

    /**
     * 我的退款申请分页
     */
    @GetMapping("/my")
    public Result<Result.PageData<Refund>> my(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(refundService.myList(userId, page, size));
    }

    /**
     * 某订单的退款申请记录(本人)
     */
    @GetMapping("/order/{orderId}")
    public Result<List<Refund>> byOrder(
            @PathVariable Long orderId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return Result.success(refundService.listByOrder(orderId, userId));
    }
}
