package com.example.product.controller;

import com.example.common.entity.Product;
import com.example.common.result.Result;
import com.example.product.dto.AdminProductAuditRequest;
import com.example.product.dto.AdminProductStatusRequest;
import com.example.product.service.AdminProductService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理端商品管理控制器(薄控制器): 网关 /admin/product/** 路由转发。
 * 网关已校验 JWT 且 role=admin, Service 内再次校验角色。
 * 商品审核: /admin/product/{id}/audit 审核通过/拒绝(拒绝必须填原因)。
 */
@RestController
@RequestMapping("/admin/product")
public class AdminProductController {

    @Resource
    private AdminProductService adminProductService;

    /**
     * 全量商品分页(含下架商品), 支持审核状态筛选
     * @param status      可选: 1-上架 0-下架
     * @param auditStatus 可选: 0-待审核 1-审核通过 2-审核拒绝
     */
    @GetMapping("/list")
    public Result<Result.PageData<Product>> list(
            @RequestHeader("X-User-Role") String role,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer auditStatus,
            @RequestParam(required = false) Long merchantId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(
                adminProductService.list(role, keyword, status, auditStatus, merchantId, page, size));
    }

    /**
     * 商品统计(数据看板, 含待审核数量)
     */
    @GetMapping("/count")
    public Result<Map<String, Long>> count(@RequestHeader("X-User-Role") String role) {
        return Result.success(adminProductService.count(role));
    }

    /**
     * 商品审核: 通过(1)/拒绝(2), 拒绝必须填写原因
     */
    @PutMapping("/{id}/audit")
    public Result<Void> audit(
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id,
            @RequestBody AdminProductAuditRequest req) {
        adminProductService.audit(role, id, req == null ? null : req.getAuditStatus(),
                req == null ? null : req.getAuditReason());
        return Result.success();
    }

    /**
     * 强制上架/下架(上架要求商品已通过审核)
     */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id,
            @RequestBody AdminProductStatusRequest req) {
        adminProductService.updateStatus(role, id, req == null ? null : req.getStatus());
        return Result.success();
    }
}
