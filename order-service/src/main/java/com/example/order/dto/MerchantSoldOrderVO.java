package com.example.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家"我卖出的订单"VO(一行 = 一个订单组)。
 *
 * 说明: 一个订单可包含多个商家的商品, 明细按 merchant_id 过滤分页后,
 * 同一订单的多个"属于当前商家"的明细合并到同一个组(items), 订单级字段
 * (订单号/下单时间/支付状态/收货人)从 t_order 取。
 */
@Data
public class MerchantSoldOrderVO {
    /** 订单ID */
    private Long orderId;
    /** 订单号 */
    private String orderNo;
    /** 下单时间 */
    private LocalDateTime createTime;
    /** 订单支付状态: 1-待支付 2-已支付 3-已发货 4-已签收 5-已取消 */
    private Integer orderStatus;
    /** 支付时间(未支付为 null) */
    private LocalDateTime payTime;
    /** 收货人姓名 */
    private String receiverName;
    /** 收货人电话 */
    private String receiverPhone;
    /** 收货地址 */
    private String receiverAddr;
    /** 订单总金额(整单, 可能含其他商家的商品) */
    private BigDecimal totalAmount;
    /** 当前商家在本订单中的商品明细 */
    private List<MerchantSoldItemVO> items;

    /**
     * 商家商品明细 VO(订单快照 + 物流信息)
     */
    @Data
    public static class MerchantSoldItemVO {
        /** 明细ID(发货/送达操作定位用) */
        private Long itemId;
        private Long productId;
        /** 商品名称快照 */
        private String productName;
        /** 商品主图快照 */
        private String productImage;
        /** 购买单价 */
        private BigDecimal price;
        /** 购买数量 */
        private Integer count;
        /** 小计金额 */
        private BigDecimal subtotal;
        /** 物流状态: 0-待发货 1-已发货 2-已送达 */
        private Integer logisticsStatus;
        /** 发货时间 */
        private LocalDateTime shipTime;
        /** 送达时间 */
        private LocalDateTime deliveryTime;
    }
}
