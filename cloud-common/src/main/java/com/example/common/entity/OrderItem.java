package com.example.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单详情实体(对应 t_order_item 表)
 */
@Data
@NoArgsConstructor
@TableName("t_order_item")
public class OrderItem {
    // @TableId 注解指定主键字段
    @TableId(type = IdType.AUTO)
    private Long id;
    // 订单ID
    private Long orderId;
    // 订单号
    private String orderNo;
    // 商品ID
    private Long productId;
    // 商品名称
    private String productName;
    // 商品图片
    private String productImage;
    // 商品单价
    private BigDecimal price;
    // 商品数量
    private Integer count;
    // 商品小计
    private BigDecimal subtotal;
    // 商家用户ID(下单时从商品快照写入, 同一订单可含多个商家的商品)
    private Long merchantId;
    // 物流状态: 0-待发货 1-已发货 2-已送达(明细级流转, 商家按明细发货/确认送达)
    private Integer logisticsStatus;
    // 发货时间
    private LocalDateTime shipTime;
    // 送达时间
    private LocalDateTime deliveryTime;
    // 创建时间
    private LocalDateTime createTime;
}
