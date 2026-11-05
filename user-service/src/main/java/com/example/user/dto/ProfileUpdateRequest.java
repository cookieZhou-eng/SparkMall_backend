package com.example.user.dto;

import lombok.Data;

/**
 * 更新个人资料请求 DTO
 * 仅允许修改"非必要字段"; username/phone/role/password 不可通过此接口修改
 */
@Data
public class ProfileUpdateRequest {
    private String nickname;    // 昵称
    private String email;       // 邮箱
    private String avatar;      // 头像URL
    private Integer gender;     // 性别: 0-未知 1-男 2-女
    private String shopName;    // 店铺名(商家)
    private String shopDesc;    // 店铺简介(商家)
}
