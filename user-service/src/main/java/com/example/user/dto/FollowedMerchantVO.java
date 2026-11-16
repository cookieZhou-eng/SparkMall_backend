package com.example.user.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 已收藏商家 VO(个人中心"收藏的商家"列表项)
 * 复用 UserVO 的展示字段 + 收藏时间
 */
@Data
public class FollowedMerchantVO {
    private Long merchantId;     // 商家用户ID
    private String username;     // 商家用户名
    private String shopName;     // 店铺名
    private String shopDesc;     // 店铺简介
    private String avatar;       // 店主头像
    private LocalDateTime followTime; // 收藏时间
}
