package com.example.user.dto;

import lombok.Data;

/**
 * 忘记密码重置请求 DTO(未登录状态, 靠短信验证码确认身份)
 */
@Data
public class PasswordResetRequest {
    private String phone;       // 手机号
    private String code;        // 短信验证码
    private String newPassword; // 新密码(明文, 长度>=6)
}
