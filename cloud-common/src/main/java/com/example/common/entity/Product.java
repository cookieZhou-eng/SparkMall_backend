package com.example.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体(对应数据库 t_product 表)
 *
 * MyBatis-Plus 注解说明:
 *  - @TableName: 指定表名(类名 Product 与表名 t_product 不一致, 必须显式声明)
 *  - @TableId:   指定主键, IdType.AUTO 表示使用数据库自增
 *  - @TableLogic: 逻辑删除字段, MP 会自动在所有 SQL 后追加 is_deleted=0 过滤
 *                  删除时改为 UPDATE ... SET is_deleted=1, 不真正物理删除
 *  - @Version:   乐观锁字段, 配合 OptimisticLockerInnerInterceptor 使用
 *                  UPDATE 时自动 version+1 且 WHERE version=旧值, 防止并发覆盖
 */
@Data
@NoArgsConstructor
@TableName("t_product")
public class Product {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long categoryId;
    private String name;
    private String subtitle;
    private String mainImage;
    private String detailImages;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer sales;
    private Integer status;
    /** 商家用户 id(商家发布的商品才有值, 平台自营为 null) */
    private Long merchantId;
    @Version
    private Integer version;
    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 可用库存(非数据库字段): 购物车/批量查询时由 product-service 从 t_product_stock 填充(stock - locked) */
    @TableField(exist = false)
    private Integer stock;
}
