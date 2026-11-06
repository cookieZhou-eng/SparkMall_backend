package com.example.user.dto;

import lombok.Data;

/**
 * 管理端启用/禁用用户请求
 */
@Data
public class AdminUserStatusRequest {
    /** 1-启用 0-禁用 */
    private Integer status;
}
