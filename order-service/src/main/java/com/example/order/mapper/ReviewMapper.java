package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.Review;
import com.example.order.dto.ReviewStatsVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

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

    /**
     * 未评价的已完成订单明细数(生成"默认好评"虚拟评价的数据量):
     * 商品所在明细属于 status=4(已完成)订单, 且该明细在 t_review 中没有评价记录。
     */
    @Select("SELECT COUNT(*) FROM t_order_item i"
            + " JOIN t_order o ON i.order_id = o.id"
            + " WHERE i.product_id = #{productId} AND o.status = 4 AND o.is_deleted = 0"
            + " AND NOT EXISTS (SELECT 1 FROM t_review r"
            + "     WHERE r.order_item_id = i.id AND r.is_deleted = 0)")
    Long countUnreviewed(@Param("productId") Long productId);

    /**
     * 未评价的已完成订单明细(最近 200 条): 用于虚拟默认好评列表。
     * 单库直查 t_user(学习项目简化); LIMIT 防止刷单场景下大表拖垮接口。
     * 返回列: orderItemId / userId / username / createTime(取订单 update_time, 近似确认收货时间)。
     */
    @Select("SELECT i.id AS orderItemId, o.user_id AS userId, u.username AS username,"
            + " o.update_time AS createTime"
            + " FROM t_order_item i"
            + " JOIN t_order o ON i.order_id = o.id"
            + " JOIN t_user u ON o.user_id = u.id"
            + " WHERE i.product_id = #{productId} AND o.status = 4 AND o.is_deleted = 0"
            + " AND NOT EXISTS (SELECT 1 FROM t_review r"
            + "     WHERE r.order_item_id = i.id AND r.is_deleted = 0)"
            + " ORDER BY o.update_time DESC LIMIT 200")
    List<Map<String, Object>> selectUnreviewed(@Param("productId") Long productId);

    /**
     * 批量查用户昵称(真实评价回填展示昵称用): 返回列 id / username。
     */
    @Select("<script>SELECT id, username FROM t_user WHERE id IN"
            + " <foreach collection='ids' item='x' open='(' separator=',' close=')'>#{x}</foreach>"
            + "</script>")
    List<Map<String, Object>> selectUsernames(@Param("ids") List<Long> ids);
}
