package com.example.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单物流单实体(对应 t_order_logistics 表, 模拟快递)
 *
 * 设计说明:
 *  - 粒度 = 订单明细(order_item_id 唯一): 同一订单可含多个商家的商品,
 *    各明细由对应商家独立发货、独立运单, 与 t_order_item.logistics_status(发货/送达状态)相互独立。
 *  - 轨迹推进: 商家发货时创建(状态 0-待揽收), 之后由 order-service 定时任务
 *    每 60 秒推进一条轨迹(已揽收→运输中→派送中→已签收), 签收即止。
 */
@Data
@NoArgsConstructor
@TableName("t_order_logistics")
public class OrderLogistics {
    @TableId(type = IdType.AUTO)
    private Long id;
    // 订单明细ID(唯一, 一条明细一个运单)
    private Long orderItemId;
    // 订单号(冗余, 便于按订单查询)
    private String orderNo;
    // 发货商家用户ID(冗余)
    private Long merchantId;
    // 承运商名称(顺丰速运/中通快递/圆通速递/韵达快递/EMS)
    private String company;
    // 运单号
    private String trackingNo;
    // 轨迹状态: 0-待揽收 1-已揽收 2-运输中 3-派送中 4-已签收
    private Integer status;
    // 最新轨迹描述(列表页直接展示, 免查轨迹表)
    private String lastDesc;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // 物流轨迹列表(非数据库字段, 按 trace_time 升序填充, 前端倒序渲染最新在前)
    @TableField(exist = false)
    private List<OrderTrace> traces;
}
