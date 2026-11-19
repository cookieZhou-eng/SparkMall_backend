package com.example.order.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 物流信息 VO(模拟快递): 物流单 + 轨迹时间线。
 * 用于商家端"查看物流"弹窗; 用户/管理端订单详情直接通过明细的 logistics 字段下发。
 */
@Data
public class OrderLogisticsVO {
    /** 订单明细ID */
    private Long itemId;
    /** 商品名称快照 */
    private String productName;
    /** 承运商名称 */
    private String company;
    /** 运单号 */
    private String trackingNo;
    /** 轨迹状态: 0-待揽收 1-已揽收 2-运输中 3-派送中 4-已签收 */
    private Integer status;
    /** 最新轨迹描述 */
    private String lastDesc;
    /** 轨迹时间线(按时间升序) */
    private List<TraceVO> traces;

    /**
     * 单条轨迹节点
     */
    @Data
    public static class TraceVO {
        /** 轨迹节点: 商家发货/已揽收/运输中/派送中/已签收 */
        private String title;
        /** 轨迹描述 */
        private String description;
        /** 所在城市/网点(模拟, 可为空) */
        private String location;
        /** 轨迹时间 */
        private LocalDateTime traceTime;
    }
}
