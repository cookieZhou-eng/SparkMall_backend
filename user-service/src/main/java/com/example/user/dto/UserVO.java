package com.example.user.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户信息 VO(脱敏后的前端展示视图, 不含密码等敏感字段)
 */
@Data
public class UserVO {
    private Long id;            // 用户ID
    private String username;    // 用户名
    private String nickname;    // 昵称
    private String phone;       // 手机号
    private String email;       // 邮箱
    private String avatar;      // 头像URL
    private Integer gender;     // 性别
    private String role;        // 角色: customer-顾客 / merchant-商家
    private String shopName;    // 店铺名(商家)
    private String shopDesc;    // 店铺简介(商家)
    private LocalDateTime createTime; // 注册时间
    /** 累计销售额(仅商家公开信息接口返回, 已支付订单的商品金额合计, 顾客为 null) */
    private BigDecimal totalSales;
}
