package com.example.order.controller;

import com.example.common.entity.Review;
import com.example.common.result.Result;
import com.example.order.dto.ReviewCreateRequest;
import com.example.order.dto.ReviewStatsVO;
import com.example.order.service.ReviewService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单评价接口。
 * Controller 保持薄: 只接收参数、调用 Service、封装 Result。
 */
@RestController
@RequestMapping("/review")
public class ReviewController {

    @Resource
    private ReviewService reviewService;

    /**
     * 提交评价(登录)
     */
    @PostMapping
    public Result<Review> create(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                 @RequestBody ReviewCreateRequest req) {
        return Result.success(reviewService.create(userId, req));
    }

    /**
     * 查询某订单的评价(仅订单归属人)
     */
    @GetMapping("/order/{orderId}")
    public Result<List<Review>> listByOrder(
            @PathVariable Long orderId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return Result.success(reviewService.listByOrder(orderId, userId));
    }

    /**
     * 商品评价分页(公开, 网关白名单放行)
     */
    @GetMapping("/product/{productId}")
    public Result<Result.PageData<Review>> pageByProduct(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(reviewService.pageByProduct(productId, page, size));
    }

    /**
     * 商品评价统计(公开): 平均分 + 总数
     */
    @GetMapping("/product/{productId}/stats")
    public Result<ReviewStatsVO> stats(@PathVariable Long productId) {
        return Result.success(reviewService.stats(productId));
    }
}
