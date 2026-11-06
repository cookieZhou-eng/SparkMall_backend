package com.example.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款售后实体(对应 t_refund 表)
 *
 * 状态机: 1-待审核 2-已通过 3-已拒绝 4-退款中 5-已退款
 * 审核通过时: 订单状态置为 6(已退款), 支付流水置为 3(已退款), 并回补商品库存。
 */
@Data
@NoArgsConstructor
@TableName("t_refund")
public class Refund {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String refundNo;
    private Long orderId;
    private String orderNo;
    private Long userId;
    private BigDecimal amount;
    /** 退款类型: 1-仅退款 2-退货退款 */
    private Integer type;
    private String reason;
    private String description;
    /** 凭证图片 URL, 逗号分隔, 最多 3 张 */
    private String images;
    /** 状态: 1-待审核 2-已通过 3-已拒绝 4-退款中 5-已退款 */
    private Integer status;
    private String auditRemark;
    private LocalDateTime applyTime;
    private LocalDateTime auditTime;
    private LocalDateTime refundTime;
    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
