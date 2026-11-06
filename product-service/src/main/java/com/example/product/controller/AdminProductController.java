package com.example.product.controller;

import com.example.common.entity.Product;
import com.example.common.result.Result;
import com.example.product.dto.AdminProductStatusRequest;
import com.example.product.service.AdminProductService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理端商品管理控制器(薄控制器): 网关 /admin/product/** 路由转发。
 * 网关已校验 JWT 且 role=admin, Service 内再次校验角色。
 */
@RestController
@RequestMapping("/admin/product")
public class AdminProductController {

    @Resource
    private AdminProductService adminProductService;

    /**
     * 全量商品分页(含下架商品)
     * @param status 可选: 1-上架 0-下架
     */
    @GetMapping("/list")
    public Result<Result.PageData<Product>> list(
            @RequestHeader("X-User-Role") String role,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long merchantId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(adminProductService.list(role, keyword, status, merchantId, page, size));
    }

    /**
     * 商品统计(数据看板)
     */
    @GetMapping("/count")
    public Result<Map<String, Long>> count(@RequestHeader("X-User-Role") String role) {
        return Result.success(adminProductService.count(role));
    }

    /**
     * 强制上架/下架
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
