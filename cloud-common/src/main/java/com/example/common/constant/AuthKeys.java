package com.example.common.constant;

/**
 * 认证相关 Redis key 约定(写入方: user-service, 读取方: gateway-service)。
 *
 * 两端必须使用同一前缀, 修改时需同步网关与用户服务。
 */
public final class AuthKeys {

    private AuthKeys() {
    }

    /** JWT 黑名单 key 前缀(退出登录写入, 网关校验) */
    public static final String TOKEN_BLACKLIST_PREFIX = "auth:token:blacklist:";

    /**
     * 账号状态 key 前缀: 值 "1"=启用, "0"=禁用。
     * 登录成功/启用账号时写 "1", 管理端禁用时写 "0";
     * 网关在鉴权通过后读取, 值为 "0" 直接 401, 实现"禁用账号的存量 token 立即失效"。
     * TTL 与 JWT 有效期(7 天)对齐, 过期自动清理。
     */
    public static final String USER_STATUS_PREFIX = "auth:user:status:";
}
