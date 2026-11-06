package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.Review;
import com.example.order.dto.ReviewStatsVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 订单评价 Mapper
 */
@Mapper
public interface ReviewMapper extends BaseMapper<Review> {

    /**
     * 商品评价统计: 平均分 + 评价总数(一次聚合查询, 避免两次 count)
     * t_review 带 @TableLogic, 这里手工追加 is_deleted = 0 过滤。
     */
    @Select("SELECT COALESCE(AVG(rating), 0) AS avgRating, COUNT(*) AS total"
            + " FROM t_review WHERE product_id = #{productId} AND is_deleted = 0")
    ReviewStatsVO stats(@Param("productId") Long productId);
}
