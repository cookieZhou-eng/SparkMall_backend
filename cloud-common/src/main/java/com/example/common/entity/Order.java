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
import java.util.List;

/**
 * 订单实体(对应 t_order 表)
 */
@Data
@NoArgsConstructor
@TableName("t_order")
public class Order {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    // 订单金额
    private BigDecimal totalAmount;
    // 订单商品数量
    private Integer totalCount;
    // 订单状态
    private Integer status;
    // 收货人姓名
    private String receiverName;
    // 收货人手机号
    private String receiverPhone;
    // 收货人地址
    private String receiverAddr;
    // 订单备注
    private String remark;
    // 支付时间
    private LocalDateTime payTime;
    // version 字段, 用于乐观锁
    @Version
    private Integer version;
    // 逻辑删除字段
    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // 订单详情列表(非数据库字段, 用于订单详情页一次查全)
    @TableField(exist = false)
    private List<OrderItem> items;

    // 待支付剩余秒数(非数据库字段, 服务端按 createTime + 超时时间实时计算, 供前端倒计时展示)
    @TableField(exist = false)
    private Long remainingSeconds;

    // 最近一次退款申请的状态/单号(非数据库字段, 订单列表组装时填充, 供前端展示"退款中"并控制申请按钮)
    @TableField(exist = false)
    private Integer refundStatus;

    @TableField(exist = false)
    private String refundNo;
}
