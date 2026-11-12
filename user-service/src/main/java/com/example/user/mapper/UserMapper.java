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

    /**
     * 商家累计销售量 = SUM(t_order_item.count), 统计口径与销售额一致(已支付订单, 状态 2/3/4)。
     * 返回 Long 以兼容 SUM 为 NULL 时 COALESCE 兜底的 0 值。
     */
    @Select("SELECT COALESCE(SUM(i.count), 0)"
            + " FROM t_order_item i"
            + " JOIN t_order o ON i.order_id = o.id"
            + " WHERE i.merchant_id = #{merchantId}"
            + " AND o.status IN (2, 3, 4) AND o.is_deleted = 0")
    Long sumMerchantSalesCount(@Param("merchantId") Long merchantId);

    /**
     * 商家累计评价数 = 该商家全部商品收到的真实评价数(t_review JOIN t_product 按 merchant_id 过滤)。
     */
    @Select("SELECT COUNT(*)"
            + " FROM t_review r"
            + " JOIN t_product p ON r.product_id = p.id"
            + " WHERE p.merchant_id = #{merchantId}"
            + " AND p.is_deleted = 0 AND r.is_deleted = 0")
    Long countMerchantReviews(@Param("merchantId") Long merchantId);

    /**
     * 商家好评数 = 评分 >= 4 星的评价数(统计口径: 4~5 星算好评, 与主流电商一致)。
     */
    @Select("SELECT COALESCE(SUM(r.rating >= 4), 0)"
            + " FROM t_review r"
            + " JOIN t_product p ON r.product_id = p.id"
            + " WHERE p.merchant_id = #{merchantId}"
            + " AND p.is_deleted = 0 AND r.is_deleted = 0")
    Long countMerchantGoodReviews(@Param("merchantId") Long merchantId);
}
