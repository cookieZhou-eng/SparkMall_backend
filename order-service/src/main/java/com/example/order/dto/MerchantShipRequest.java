package com.example.order.dto;

import lombok.Data;

/**
 * 商家发货请求参数: 承运商 + 运单号(均为必填)。
 * 发货成功后创建模拟物流单(t_order_logistics), 由定时任务推进轨迹。
 */
@Data
public class MerchantShipRequest {
    /** 承运商名称: 顺丰速运/中通快递/圆通速递/韵达快递/EMS */
    private String company;
    /** 运单号(5-30 位字母/数字/短横线) */
    private String trackingNo;
}
