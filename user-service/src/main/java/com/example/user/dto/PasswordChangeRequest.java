package com.example.user.dto;

import lombok.Data;

/**
 * 修改密码请求 DTO(已登录状态, 需校验旧密码)
 */
@Data
public class PasswordChangeRequest {
    private String oldPassword; // 旧密码(明文)
    private String newPassword; // 新密码(明文, 长度>=6)
}
