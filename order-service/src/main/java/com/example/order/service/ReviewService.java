package com.example.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.entity.Order;
import com.example.common.entity.OrderItem;
import com.example.common.entity.Review;
import com.example.common.result.Result;
import com.example.order.dto.ReviewCreateRequest;
import com.example.order.dto.ReviewStatsVO;
import com.example.order.exception.BusinessException;
import com.example.order.mapper.OrderItemMapper;
import com.example.order.mapper.OrderMapper;
import com.example.order.mapper.ReviewMapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单评价业务。
 *
 * 规则:
 *  - 只有订单状态=4(已完成)且订单属于当前用户时才能评价;
 *  - 一个订单明细只能评价一次(order_item_id 唯一索引 + 先查一次做友好提示);
 *  - 评分 1-5, 内容最多 500 字, 图片最多 3 张。
 */
@Service
public class ReviewService {

    private static final int MAX_CONTENT_LENGTH = 500;
    private static final int MAX_IMAGES = 3;
    private static final int MAX_PAGE_SIZE = 100;

    @Resource
    private ReviewMapper reviewMapper;
    @Resource
    private OrderMapper orderMapper;
    @Resource
    private OrderItemMapper orderItemMapper;

    /**
     * 提交评价
     */
    public Review create(Long userId, ReviewCreateRequest req) {
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        if (req == null || req.getOrderItemId() == null) {
            throw new BusinessException(400, "订单明细不能为空");
        }
        if (req.getRating() == null || req.getRating() < 1 || req.getRating() > 5) {
            throw new BusinessException(400, "评分必须在 1-5 星之间");
        }
        String content = req.getContent() == null ? "" : req.getContent().trim();
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(400, "评价内容不能超过 500 字");
        }
        String images = joinImages(req.getImages());

        OrderItem item = orderItemMapper.selectById(req.getOrderItemId());
        if (item == null) {
            throw new BusinessException(404, "订单明细不存在");
        }
        Order order = orderMapper.selectById(item.getOrderId());
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(403, "无权评价该订单");
        }
        if (order.getStatus() == null || order.getStatus() != 4) {
            throw new BusinessException(400, "仅已完成的订单可以评价");
        }
        Long exists = reviewMapper.selectCount(
                new LambdaQueryWrapper<Review>().eq(Review::getOrderItemId, item.getId()));
        if (exists != null && exists > 0) {
            throw new BusinessException(400, "该商品已评价, 请勿重复提交");
        }

        Review review = new Review();
        review.setOrderItemId(item.getId());
        review.setOrderId(order.getId());
        review.setProductId(item.getProductId());
        review.setUserId(userId);
        review.setMerchantId(item.getMerchantId());
        review.setRating(req.getRating());
        review.setContent(content);
        review.setImages(images);
        review.setAnonymous(Boolean.TRUE.equals(req.getAnonymous()));
        review.setCreateTime(LocalDateTime.now());
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // 并发重复提交时由唯一索引兜底
            throw new BusinessException(400, "该商品已评价, 请勿重复提交");
        }
        return review;
    }

    /**
     * 查询某个订单下的评价(仅订单归属人可见)
     */
    public List<Review> listByOrder(Long orderId, Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        if (orderId == null) {
            throw new BusinessException(400, "订单ID不能为空");
        }
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(403, "无权查看该订单评价");
        }
        return reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getOrderId, orderId)
                .orderByDesc(Review::getCreateTime));
    }

    /**
     * 商品评价分页(公开接口)。匿名评价会把 userId 置空, 避免对外暴露用户ID。
     */
    public Result.PageData<Review> pageByProduct(Long productId, Integer page, Integer size) {
        if (productId == null) {
            throw new BusinessException(400, "商品ID不能为空");
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        Page<Review> pageResult = reviewMapper.selectPage(new Page<>(p, s),
                new LambdaQueryWrapper<Review>()
                        .eq(Review::getProductId, productId)
                        .orderByDesc(Review::getCreateTime));
        pageResult.getRecords().forEach(r -> {
            if (Boolean.TRUE.equals(r.getAnonymous())) {
                r.setUserId(null);
            }
        });
        return new Result.PageData<>(pageResult.getTotal(), pageResult.getRecords());
    }

    /**
     * 商品评价统计: 平均分 + 总数
     */
    public ReviewStatsVO stats(Long productId) {
        if (productId == null) {
            throw new BusinessException(400, "商品ID不能为空");
        }
        ReviewStatsVO stats = reviewMapper.stats(productId);
        if (stats == null) {
            return new ReviewStatsVO(0.0, 0L);
        }
        if (stats.getTotal() == null) {
            stats.setTotal(0L);
        }
        if (stats.getAvgRating() == null) {
            stats.setAvgRating(0.0);
        }
        return stats;
    }

    /**
     * 图片 URL 列表转逗号分隔字符串, 并做数量/长度校验
     */
    private String joinImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        List<String> valid = images.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .toList();
        if (valid.size() > MAX_IMAGES) {
            throw new BusinessException(400, "最多上传 3 张图片");
        }
        String joined = String.join(",", valid);
        if (joined.length() > 1000) {
            throw new BusinessException(400, "图片地址过长");
        }
        return joined.isEmpty() ? null : joined;
    }
}
