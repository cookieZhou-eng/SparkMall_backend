package com.example.user.controller;

import com.example.common.result.Result;
import com.example.user.dto.AdminUserStatusRequest;
import com.example.user.dto.UserVO;
import com.example.user.service.AdminUserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理端用户管理控制器(薄控制器): 网关 /admin/user/** 路由转发。
 * 网关已校验 JWT 且 role=admin, Service 内再次校验角色。
 */
@RestController
@RequestMapping("/admin/user")
public class AdminUserController {

    @Resource
    private AdminUserService adminUserService;

    /**
     * 用户分页列表
     * @param roleFilter 可选角色筛选: customer / merchant / admin
     * @param status     可选状态筛选: 1-启用 0-禁用
     */
    @GetMapping("/list")
    public Result<Result.PageData<UserVO>> list(
            @RequestHeader("X-User-Role") String role,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String roleFilter,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(adminUserService.list(role, keyword, roleFilter, status, page, size));
    }

    /**
     * 用户统计(数据看板)
     */
    @GetMapping("/count")
    public Result<Map<String, Long>> count(@RequestHeader("X-User-Role") String role) {
        return Result.success(adminUserService.count(role));
    }

    /**
     * 启用/禁用用户
     */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id,
            @RequestBody AdminUserStatusRequest req) {
        adminUserService.updateStatus(role, id, req == null ? null : req.getStatus());
        return Result.success();
    }
}
