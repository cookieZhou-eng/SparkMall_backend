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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
     * 商品评价分页(公开接口): 真实评价 + 虚拟"默认好评"合并展示。
     *
     * 默认好评规则: 已完成订单(status=4)中未写评价的明细, 以 5 星 + 空内容的形式
     * 虚拟补进评价列表(拼多多式"默认好评"), 用户评价后即被真实评价取代(NOT EXISTS 过滤)。
     * 昵称一律脱敏(如 z***n), 匿名评价显示"匿名用户", 避免泄露真实身份。
     * 分页采用"全局虚拟排序": 真实评价占前 realTotal 槽位, 虚拟好评紧随其后。
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
        List<Review> records = new ArrayList<>(pageResult.getRecords());
        long realTotal = pageResult.getTotal();
        Long unreviewed = reviewMapper.countUnreviewed(productId);
        long virtualTotal = unreviewed == null ? 0L : unreviewed;
        long total = realTotal + virtualTotal;

        // 当前页剩余槽位用虚拟默认好评补齐(真实评价全局占 [0, realTotal), 虚拟占其后)
        int start = (p - 1) * s;
        int vStart = Math.max(0, start - (int) realTotal);
        int need = s - records.size();
        if (need > 0 && vStart < virtualTotal) {
            List<Map<String, Object>> rows = reviewMapper.selectUnreviewed(productId);
            for (int i = vStart; i < Math.min(rows.size(), vStart + need); i++) {
                records.add(virtualDefaultReview(rows.get(i)));
            }
        }

        // 昵称脱敏回填: 匿名 -> "匿名用户", 实名 -> z***n; 虚拟好评已带脱敏昵称不再处理
        List<Long> userIds = records.stream()
                .filter(r -> r.getUsername() == null && r.getUserId() != null)
                .map(Review::getUserId).distinct().toList();
        Map<Long, String> names = userIds.isEmpty() ? Map.of()
                : reviewMapper.selectUsernames(userIds).stream().collect(Collectors.toMap(
                        m -> ((Number) m.get("id")).longValue(),
                        m -> String.valueOf(m.get("username"))));
        for (Review r : records) {
            if (r.getUsername() != null) {
                continue;
            }
            if (Boolean.TRUE.equals(r.getAnonymous())) {
                r.setUsername("匿名用户");
            } else {
                String name = names.get(r.getUserId());
                r.setUsername(name == null ? "已购用户" : maskName(name));
            }
        }

        return new Result.PageData<>(total, records);
    }

    /**
     * 构造一条虚拟默认好评: 5 星 + 无文字内容, id 取负的明细 id(不与真实自增 id 冲突, 前端可作 key)
     */
    private Review virtualDefaultReview(Map<String, Object> row) {
        Review r = new Review();
        long orderItemId = ((Number) row.get("orderItemId")).longValue();
        r.setId(-orderItemId);
        r.setOrderItemId(orderItemId);
        r.setRating(5);
        r.setContent(null);
        r.setAnonymous(false);
        r.setUsername(maskName(String.valueOf(row.get("username"))));
        Object time = row.get("createTime");
        r.setCreateTime(time instanceof LocalDateTime t ? t : LocalDateTime.now());
        return r;
    }

    /** 昵称脱敏: 首字符 + *** + 尾字符(长度<=2 只保留首字符) */
    private String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "神秘用户";
        }
        String n = name.trim();
        if (n.length() <= 2) {
            return n.charAt(0) + "***";
        }
        return n.charAt(0) + "***" + n.charAt(n.length() - 1);
    }

    /**
     * 商品评价统计: 平均分 + 总数(含虚拟默认好评)。
     * 平均分 = (真实总分 + 5*默认好评数) / 总条数, 与评价列表口径一致。
     */
    public ReviewStatsVO stats(Long productId) {
        if (productId == null) {
            throw new BusinessException(400, "商品ID不能为空");
        }
        ReviewStatsVO real = reviewMapper.stats(productId);
        long realTotal = real == null || real.getTotal() == null ? 0L : real.getTotal();
        double realAvg = real == null || real.getAvgRating() == null ? 0.0 : real.getAvgRating();
        Long unreviewed = reviewMapper.countUnreviewed(productId);
        long virtualTotal = unreviewed == null ? 0L : unreviewed;
        long total = realTotal + virtualTotal;
        if (total == 0) {
            return new ReviewStatsVO(0.0, 0L);
        }
        double avg = (realAvg * realTotal + 5.0 * virtualTotal) / total;
        return new ReviewStatsVO(Math.round(avg * 10.0) / 10.0, total);
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
