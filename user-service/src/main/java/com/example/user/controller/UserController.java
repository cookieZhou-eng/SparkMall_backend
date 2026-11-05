package com.example.user.controller;

import com.example.common.entity.User;
import com.example.common.result.Result;
import com.example.user.dto.PasswordChangeRequest;
import com.example.user.dto.PasswordResetRequest;
import com.example.user.dto.ProfileUpdateRequest;
import com.example.user.dto.RegisterRequest;
import com.example.user.dto.SmsSendRequest;
import com.example.user.dto.UserVO;
import com.example.user.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户控制器(薄控制器)
 *
 * 分层说明:
 *  - 只做参数接收/基础校验与 Result 封装, 登录/注册/个人中心/短信/商家信息业务在 UserService。
 *  - 业务失败(UserService 抛 BusinessException)由全局异常处理器统一转成 Result。
 *  - 网关(gateway-service)对受保护接口统一校验 JWT 并把 userId/username/role 透传,
 *    info/profile 接口优先信任网关透传头, 未经网关直连时兜底从 Authorization 解析 JWT。
 *  - 公开接口(网关已放行): /user/login /user/register /user/sms/send /user/password/reset /user/merchant/{id}
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 登录接口
     * 流程: 参数校验 → UserService 校验密码 → 签发 JWT 返回
     */
    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.isBlank() || password == null) {
            return Result.fail(400, "用户名或密码不能为空");
        }
        return Result.success(userService.login(username, password));
    }

    /**
     * 注册接口(公开): 顾客/商家注册, 密码 BCrypt 加密落库
     */
    @PostMapping("/register")
    public Result<Long> register(@RequestBody RegisterRequest req) {
        if (req == null) {
            return Result.fail(400, "注册参数不能为空");
        }
        return Result.success(userService.register(req));
    }

    /**
     * 获取当前登录用户信息
     * 优先读网关透传的 X-User-Id(网关已校验过 JWT, 业务侧信任上游);
     * 未经网关直连时, 兜底从 Authorization 的 Bearer JWT 解析 userId。
     */
    @GetMapping("/info")
    public Result<User> info(
            @RequestHeader(value = "X-User-Id", required = false) String xUserId,
            @RequestHeader(value = "Authorization", required = false) String auth) {

        Long userId = userService.resolveUserId(xUserId, auth);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        return Result.success(userService.info(userId));
    }

    /**
     * 个人中心: 当前用户资料(脱敏 VO, 含 role/shopName/shopDesc/createTime)
     */
    @GetMapping("/profile")
    public Result<UserVO> profile(
            @RequestHeader(value = "X-User-Id", required = false) String xUserId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = userService.resolveUserId(xUserId, auth);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        return Result.success(userService.profile(userId));
    }

    /**
     * 更新个人资料: 仅允许修改昵称/邮箱/头像/性别/店铺信息, username/phone/role/password 不可改
     */
    @PutMapping("/profile")
    public Result<UserVO> updateProfile(
            @RequestHeader(value = "X-User-Id", required = false) String xUserId,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody ProfileUpdateRequest req) {
        Long userId = userService.resolveUserId(xUserId, auth);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        return Result.success(userService.updateProfile(userId, req));
    }

    /**
     * 修改密码(已登录): 校验旧密码 → BCrypt 加密更新新密码
     */
    @PutMapping("/password")
    public Result<Void> changePassword(
            @RequestHeader(value = "X-User-Id", required = false) String xUserId,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody PasswordChangeRequest req) {
        Long userId = userService.resolveUserId(xUserId, auth);
        if (userId == null) {
            return Result.fail(401, "未登录或登录已过期");
        }
        userService.changePassword(userId, req);
        return Result.success();
    }

    /**
     * 发送短信验证码(公开, 忘记密码流程): 模拟发送, 验证码只打日志
     * 60 秒内不允许重复发送(Redis setIfAbsent 限流)
     */
    @PostMapping("/sms/send")
    public Result<Void> sendSms(@RequestBody SmsSendRequest req) {
        userService.sendSms(req);
        return Result.success();
    }

    /**
     * 忘记密码重置(公开): 手机号 + 短信验证码 + 新密码, 验证码一次性使用
     */
    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@RequestBody PasswordResetRequest req) {
        userService.resetPassword(req);
        return Result.success();
    }

    /**
     * 商家搜索(公开): 按用户名/店铺名/店铺简介模糊搜索商家, 最多 20 条;
     * keyword 为空返回前 20 个商家(便于"逛店铺")。
     * 注意: 与下方 /merchant/{id} 不冲突, Spring MVC 字面量路径优先于路径变量。
     */
    @GetMapping("/merchant/search")
    public Result<List<UserVO>> searchMerchants(@RequestParam(required = false) String keyword) {
        return Result.success(userService.searchMerchants(keyword));
    }

    /**
     * 公开查看商家信息: 仅 role=merchant 的用户可查
     */
    @GetMapping("/merchant/{id}")
    public Result<UserVO> merchantInfo(@PathVariable Long id) {
        return Result.success(userService.merchantInfo(id));
    }

    /**
     * 退出登录: token 加入 Redis 黑名单后立即失效
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        userService.logout(auth);
        return Result.success(null);
    }
}
