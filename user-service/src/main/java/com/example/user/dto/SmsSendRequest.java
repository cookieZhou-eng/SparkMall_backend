package com.example.user.dto;

import lombok.Data;

/**
 * 发送短信验证码请求 DTO(模拟发送, 验证码只打日志不返回)
 */
@Data
public class SmsSendRequest {
    private String phone;       // 手机号(必须是已注册用户)
}
