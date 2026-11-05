package com.example.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 商品库存实体(对应 t_product_stock 表)
 * 库存独立成表, 便于后续 Redis 预热 + Lua 原子扣减
 */
@Data
@NoArgsConstructor
@TableName("t_product_stock")
public class ProductStock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private Integer stock;       // 可用库存
    private Integer locked;      // 锁定库存(下单未支付)
    @Version
    private Integer version;     // 乐观锁(扣减库存时使用)
    private LocalDateTime updateTime;
}
