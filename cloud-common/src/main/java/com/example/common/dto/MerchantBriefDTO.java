package com.example.common.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商家简要信息 DTO(服务间内部批量查询用)。
 *
 * 使用场景:
 *  - user-service 提供 /internal/user/merchant/batch 接口, 按用户 id 批量返回商家公开信息;
 *  - product-service 管理端商品/审核列表通过 Feign 调用, 把 merchantId 回填成店铺名展示,
 *    避免 N+1 逐个查询。
 */
@Data
@NoArgsConstructor
public class MerchantBriefDTO {
    /** 用户 id(即 t_user.id) */
    private Long id;
    /** 登录用户名 */
    private String username;
    /** 昵称 */
    private String nickname;
    /** 店铺名 */
    private String shopName;
    /** 店铺简介 */
    private String shopDesc;
    /** 头像 URL */
    private String avatar;
    /** 状态: 1-启用 0-禁用 */
    private Integer status;
}
