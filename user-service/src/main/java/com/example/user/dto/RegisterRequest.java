package com.example.user.dto;

import lombok.Data;

/**
 * 注册请求 DTO
 * role=customer 时 shopName/shopDesc 忽略; role=merchant 时 shopName 必填
 */
@Data
public class RegisterRequest {
    private String username;    // 用户名(登录名, 唯一)
    private String password;    // 明文密码(服务端 BCrypt 加密后落库)
    private String phone;       // 手机号(唯一)
    private String role;        // 角色: customer-顾客 / merchant-商家
    private String shopName;    // 店铺名(商家必填)
    private String shopDesc;    // 店铺简介(商家可选)
}
