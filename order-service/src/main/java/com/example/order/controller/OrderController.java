package com.example.order.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.example.common.entity.Order;
import com.example.common.entity.Payment;
import com.example.common.result.Result;
import com.example.order.dto.OrderCancelRequest;
import com.example.order.dto.OrderConfirmRequest;
import com.example.order.dto.OrderCreateRequest;
import com.example.order.dto.OrderPayRequest;
import com.example.order.service.OrderService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

import java.util.List;

@RestController
@RequestMapping("/order")
@RefreshScope // 配置动态刷新

public class OrderController {

    @Resource
    private OrderService orderService;

    @Value("${order.desc:默认值}")
    private String orderDesc;

    /**
     * 创建订单(真实下单流程)
     * body: { userId, receiverName, receiverPhone, receiverAddr, remark, items:[{productId,count}] }
     * 不同商家的商品会拆成多个独立订单, 返回订单列表(每个订单含 items 明细)
     */
    @PostMapping("/create")
    @SentinelResource(
            value = "createOrder",
            blockHandler = "orderBlockHandler" ////限流兜底，处理429
    )
    public Result<List<Order>> createOrder(@RequestBody OrderCreateRequest req) {
        return Result.success(orderService.createOrder(req));
    }

    /**
     * 限流兜底方法 blockHandler
     * 参数：和原方法完全一致，最后追加 BlockException ex
     */
    public Result orderBlockHandler(OrderCreateRequest orderDTO, BlockException ex){
        // 返回你项目统一的json，前端就能拿到msg，不再报429网络异常
        return Result.fail(429,"下单请求过于繁忙，请稍后再试");
    }

    /**
     * 秒杀下单(抢到资格后调用): 库存已在确认阶段锁定, 不重复 lockStock
     * body: 同 create(仅单个商品条目)
     */
    @PostMapping("/seckillCreate")
    public Result<Order> seckillCreate(@RequestBody OrderCreateRequest req) {
        return Result.success(orderService.createSeckillOrder(req));
    }

    /**
     * 模拟支付
     * body: { orderNo, userId, channel }
     * 支付成功: 订单状态 1->2, 落支付流水, 扣减库存(锁定转已售)
     */
    @PostMapping("/pay")
    public Result<Payment> pay(@RequestBody OrderPayRequest req) {
        return Result.success(orderService.pay(req));
    }

    /**
     * 取消订单(用户手动取消待支付订单, 释放锁定的库存)
     * body: { orderNo, userId }
     */
    @PostMapping("/cancel")
    public Result<Void> cancel(@RequestBody OrderCancelRequest req) {
        orderService.cancelOrder(req);
        return Result.success(null);
    }

    /**
     * 确认收货(用户操作): 订单状态 3(已发货/待收货) -> 4(已完成)
     * body: { orderNo }, userId 由网关透传的请求头 X-User-Id 提供
     */
    @PutMapping("/confirm")
    public Result<Void> confirm(@RequestBody OrderConfirmRequest req,
                                @RequestHeader("X-User-Id") Long userId) {
        orderService.confirmReceive(req.getOrderNo(), userId);
        return Result.success();
    }

    /**
     * 我的订单列表(按下单时间倒序, 带商品明细与待支付剩余倒计时)
     * @param status 可选状态筛选(null=全部): 1-待支付 2-已支付/待发货 3-已发货/待收货 4-已完成 5-已取消
     */
    @GetMapping("/my")
    public Result<List<Order>> myOrders(@RequestParam Long userId,
                                        @RequestParam(required = false) Integer status) {
        return Result.success(orderService.listByUser(userId, status));
    }

    /**
     * 订单详情(带商品明细与待支付剩余倒计时)
     */
    @GetMapping("/{id}")
    public Result<Order> detail(@PathVariable Long id) {
        Order order = orderService.detail(id);
        if (order == null) {
            return Result.fail(404, "订单不存在");
        }
        return Result.success(order);
    }

    @GetMapping("/desc")
    public Result<String> getDesc() {
        return Result.success(orderDesc);
    }
}
