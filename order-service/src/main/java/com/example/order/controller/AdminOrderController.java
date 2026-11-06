package com.example.order.controller;

import com.example.common.entity.Order;
import com.example.common.result.Result;
import com.example.order.service.AdminOrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理端订单控制器(薄控制器): 网关 /admin/order/** 路由转发。
 * 网关已校验 JWT 且 role=admin, Service 内再次校验角色。
 */
@RestController
@RequestMapping("/admin/order")
public class AdminOrderController {

    @Resource
    private AdminOrderService adminOrderService;

    /**
     * 全量订单分页
     * @param status 可选: 1-待支付 2-已支付/待发货 3-已发货 4-已完成 5-已取消
     */
    @GetMapping("/list")
    public Result<Result.PageData<Order>> list(
            @RequestHeader("X-User-Role") String role,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(adminOrderService.list(role, status, orderNo, userId, page, size));
    }

    /**
     * 数据看板统计
     */
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(@RequestHeader("X-User-Role") String role) {
        return Result.success(adminOrderService.dashboard(role));
    }

    /**
     * 订单详情(含商品明细)
     */
    @GetMapping("/{id}")
    public Result<Order> detail(
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id) {
        return Result.success(adminOrderService.detail(role, id));
    }
}
