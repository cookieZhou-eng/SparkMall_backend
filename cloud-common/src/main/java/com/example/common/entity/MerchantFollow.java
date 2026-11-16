package com.example.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 商家收藏实体(对应 t_merchant_follow 表)
 * 顾客收藏感兴趣的店铺; (userId, merchantId) 唯一, 取消收藏为物理删除
 */
@Data
@NoArgsConstructor
@TableName("t_merchant_follow")
public class MerchantFollow {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 收藏者用户ID */
    private Long userId;
    /** 被收藏的商家用户ID */
    private Long merchantId;
    private LocalDateTime createTime;
}
