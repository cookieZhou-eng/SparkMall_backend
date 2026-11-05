package com.example.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商家经营统计 VO(GET /order/merchant/stats)。
 *
 * 统计口径:
 *  - totalSales      总销售额 = SUM(price * count), 仅统计已支付订单(状态 2/3/4, 当前流程只会到 2)
 *  - soldItemCount   卖出商品件数 = SUM(count), 仅统计已支付订单
 *  - pendingShipCount待发货明细数 = 已支付订单中 logistics_status=0 的明细条数
 *  - paidOrderCount  已支付订单数 = 含本商家商品且已支付的订单数(去重)
 */
@Data
public class MerchantStatsVO {
    /** 总销售额(¥) */
    private BigDecimal totalSales;
    /** 卖出商品总件数 */
    private Long soldItemCount;
    /** 待发货明细数 */
    private Long pendingShipCount;
    /** 已支付订单数 */
    private Long paidOrderCount;
}
