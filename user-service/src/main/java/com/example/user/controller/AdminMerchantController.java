package com.example.user.controller;

import com.example.common.result.Result;
import com.example.user.dto.UserVO;
import com.example.user.service.AdminMerchantService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理端商家管理控制器(薄控制器): 网关 /admin/merchant/** 路由转发。
 * 网关已校验 JWT 且 role=admin, Service 内再次校验角色。
 *
 * 功能: 商家列表(店铺信息) / 商家统计 / 商家详情 / 启用禁用商家
 * (禁用商家会级联下架其在售商品, 由 Service 调 product-service 内部接口完成)。
 */
@RestController
@RequestMapping("/admin/merchant")
public class AdminMerchantController {

    @Resource
    private AdminMerchantService adminMerchantService;

    /**
     * 商家分页列表(店铺名/简介/状态/注册时间)
     */
    @GetMapping("/list")
    public Result<Result.PageData<UserVO>> list(
            @RequestHeader("X-User-Role") String role,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(adminMerchantService.list(role, keyword, status, page, size));
    }

    /**
     * 商家统计(数据看板): 总数/启用/禁用
     */
    @GetMapping("/count")
    public Result<Map<String, Long>> count(@RequestHeader("X-User-Role") String role) {
        return Result.success(adminMerchantService.count(role));
    }

    /**
     * 商家详情
     */
    @GetMapping("/{id}")
    public Result<UserVO> detail(@RequestHeader("X-User-Role") String role,
                                 @PathVariable Long id) {
        return Result.success(adminMerchantService.detail(role, id));
    }

    /**
     * 启用/禁用商家(禁用时级联下架其在售商品, 返回下架数量)
     */
    @PutMapping("/{id}/status")
    public Result<Map<String, Object>> updateStatus(
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id,
            @RequestBody AdminMerchantStatusRequest req) {
        return Result.success(adminMerchantService.updateStatus(role, id,
                req == null ? null : req.getStatus()));
    }

    /** 启用/禁用商家请求体 */
    @lombok.Data
    public static class AdminMerchantStatusRequest {
        /** 状态: 1-启用 0-禁用 */
        private Integer status;
    }
}
