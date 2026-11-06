package com.example.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 购物车实体(对应 t_cart 表)
 *
 * 同一用户同一商品只有一行(user_id + product_id 唯一), 重复加购时数量累加。
 * 商品名称/价格/图片/库存不落库, 由商品服务实时查询, 避免价格变化后购物车展示过期数据。
 */
@Data
@NoArgsConstructor
@TableName("t_cart")
public class Cart {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long productId;
    private Integer count;
    /** 是否勾选: 1-勾选 0-未勾选 */
    private Integer checked;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
