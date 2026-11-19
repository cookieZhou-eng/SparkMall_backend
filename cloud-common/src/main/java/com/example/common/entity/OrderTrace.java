package com.example.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 物流轨迹实体(对应 t_order_trace 表, 模拟快递)
 * 每次轨迹推进插入一行, 按 trace_time 升序查询, 前端 el-timeline 倒序展示。
 */
@Data
@NoArgsConstructor
@TableName("t_order_trace")
public class OrderTrace {
    @TableId(type = IdType.AUTO)
    private Long id;
    // 物流单ID
    private Long logisticsId;
    // 轨迹节点: 商家发货/已揽收/运输中/派送中/已签收
    private String title;
    // 轨迹描述
    private String description;
    // 轨迹所在城市/网点(模拟, 可为空)
    private String location;
    // 轨迹发生时间
    private LocalDateTime traceTime;
    private LocalDateTime createTime;
}
