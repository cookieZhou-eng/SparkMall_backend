package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 订单 Mapper
 * 继承 BaseMapper<Order> 后自动获得单表 CRUD, 无需写 SQL
 * 注意: t_order 上有 @TableLogic(isDeleted), MP 会自动追加 is_deleted=0 过滤
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 已支付订单成交总额(状态 2/3/4: 已支付、已发货、已完成)
     */
    @Select("SELECT COALESCE(SUM(total_amount), 0) FROM t_order"
            + " WHERE status IN (2, 3, 4) AND is_deleted = 0")
    BigDecimal sumPaidAmount();

    /**
     * 今日成交总额(按支付时间统计)
     */
    @Select("SELECT COALESCE(SUM(total_amount), 0) FROM t_order"
            + " WHERE status IN (2, 3, 4) AND is_deleted = 0 AND DATE(pay_time) = CURDATE()")
    BigDecimal sumTodayPaidAmount();
}
