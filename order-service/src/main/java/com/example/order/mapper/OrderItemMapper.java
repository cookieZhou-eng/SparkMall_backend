package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.OrderItem;
import com.example.order.dto.MerchantStatsVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 订单详情 Mapper(订单快照数据)
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * 商家经营统计(单条 JOIN 聚合): t_order_item 按商家过滤, JOIN t_order 取支付状态。
     * 统计口径见 MerchantStatsVO 注释; 已支付=订单状态 IN (2,3,4)(当前流程支付后固定为 2)。
     * t_order 带 @TableLogic, 这里手工追加 is_deleted=0 过滤。
     */
    @Select("SELECT"
            + " COALESCE(SUM(CASE WHEN o.status IN (2, 3, 4) THEN i.price * i.count ELSE 0 END), 0) AS total_sales,"
            + " COALESCE(SUM(CASE WHEN o.status IN (2, 3, 4) THEN i.count ELSE 0 END), 0) AS sold_item_count,"
            + " COALESCE(SUM(CASE WHEN o.status IN (2, 3, 4) AND i.logistics_status = 0 THEN 1 ELSE 0 END), 0) AS pending_ship_count,"
            + " COUNT(DISTINCT CASE WHEN o.status IN (2, 3, 4) THEN o.id END) AS paid_order_count"
            + " FROM t_order_item i"
            + " JOIN t_order o ON i.order_id = o.id"
            + " WHERE i.merchant_id = #{merchantId} AND o.is_deleted = 0")
    MerchantStatsVO merchantStats(@Param("merchantId") Long merchantId);
}
