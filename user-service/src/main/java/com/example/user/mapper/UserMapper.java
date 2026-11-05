package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 用户 Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 商家累计销售额 = SUM(t_order_item.price * count), 仅统计已支付订单(状态 2/3/4)。
     * 跨服务数据聚合的简化处理: 本项目为学习项目, user-service 与 order-service 共用同一个
     * MySQL 单库(spark_mall), 故直接跨表 JOIN 查询; 微服务规范做法应由 user-service 通过
     * Feign 调 order-service 聚合(或走数据仓库/冗余表), 此处权衡简化。
     */
    @Select("SELECT COALESCE(SUM(i.price * i.count), 0)"
            + " FROM t_order_item i"
            + " JOIN t_order o ON i.order_id = o.id"
            + " WHERE i.merchant_id = #{merchantId}"
            + " AND o.status IN (2, 3, 4) AND o.is_deleted = 0")
    BigDecimal sumMerchantTotalSales(@Param("merchantId") Long merchantId);
}
