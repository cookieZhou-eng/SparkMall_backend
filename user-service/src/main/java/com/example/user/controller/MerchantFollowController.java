package com.example.user.controller;

import com.example.common.result.Result;
import com.example.user.dto.FollowedMerchantVO;
import com.example.user.service.MerchantFollowService;
import com.example.user.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商家收藏控制器(薄控制器)
 *
 * 鉴权: /user/follow/** 不在网关白名单, 网关校验 JWT 后透传 X-User-Id;
 * 未经网关直连时兜底从 Authorization 解析(与 UserController 同一套约定)。
 */
@RestController
@RequestMapping("/user/follow")
public class MerchantFollowController {

    @Resource
    private MerchantFollowService followService;
    @Resource
    private UserService userService;

    /** 收藏商家(幂等) */
    @PostMapping("/{merchantId}")
    public Result<Void> follow(
            @RequestHeader(value = "X-User-Id", required = false) String xUserId,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable Long merchantId) {
        Long userId = userService.resolveUserId(xUserId, auth);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        followService.follow(userId, merchantId);
        return Result.success();
    }

    /** 取消收藏(幂等) */
    @DeleteMapping("/{merchantId}")
    public Result<Void> unfollow(
            @RequestHeader(value = "X-User-Id", required = false) String xUserId,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable Long merchantId) {
        Long userId = userService.resolveUserId(xUserId, auth);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        followService.unfollow(userId, merchantId);
        return Result.success();
    }

    /** 是否已收藏该商家(店铺页按钮初始状态) */
    @GetMapping("/{merchantId}/status")
    public Result<Boolean> isFollowed(
            @RequestHeader(value = "X-User-Id", required = false) String xUserId,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable Long merchantId) {
        Long userId = userService.resolveUserId(xUserId, auth);
        if (userId == null) {
            return Result.success(false); // 未登录视为未收藏, 不弹 401 打扰浏览
        }
        return Result.success(followService.isFollowed(userId, merchantId));
    }

    /** 我的收藏商家列表(个人中心"收藏的商家") */
    @GetMapping("/list")
    public Result<List<FollowedMerchantVO>> list(
            @RequestHeader(value = "X-User-Id", required = false) String xUserId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = userService.resolveUserId(xUserId, auth);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        return Result.success(followService.listFollowed(userId));
    }
}
