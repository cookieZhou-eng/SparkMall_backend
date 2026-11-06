package com.example.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.entity.Order;
import com.example.common.entity.Refund;
import com.example.common.result.Result;
import com.example.order.exception.BusinessException;
import com.example.order.mapper.OrderMapper;
import com.example.order.mapper.RefundMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端订单管理业务。
 *
 * 分层说明:
 *  - Controller 只做参数接收与 Result 封装。
 *  - 网关已校验 role=admin 并透传 X-User-Role, 这里再校验一次(双保险)。
 *  - 列表只查订单主表(不填充明细), 需要明细时走详情接口, 避免分页时的额外 IN 查询开销。
 */
@Service
public class AdminOrderService {

    private static final int MAX_PAGE_SIZE = 100;

    @Resource
    private OrderMapper orderMapper;
    @Resource
    private RefundMapper refundMapper;
    @Resource
    private OrderService orderService;

    /**
     * 全量订单分页, 支持状态、订单号、用户筛选
     */
    public Result.PageData<Order> list(String role, Integer status, String orderNo,
                                       Long userId, Integer page, Integer size) {
        checkAdmin(role);
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, MAX_PAGE_SIZE);

        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }
        if (orderNo != null && !orderNo.isBlank()) {
            wrapper.like(Order::getOrderNo, orderNo.trim());
        }
        if (userId != null) {
            wrapper.eq(Order::getUserId, userId);
        }
        wrapper.orderByDesc(Order::getCreateTime);

        Page<Order> pageResult = orderMapper.selectPage(new Page<>(p, s), wrapper);
        return new Result.PageData<>(pageResult.getTotal(), pageResult.getRecords());
    }

    /**
     * 订单详情(含商品明细与待支付剩余时间)
     */
    public Order detail(String role, Long id) {
        checkAdmin(role);
        Order order = orderService.detail(id);
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    /**
     * 数据看板: 订单总数、成交总额、今日订单/金额、各状态订单数
     */
    public Map<String, Object> dashboard(String role) {
        checkAdmin(role);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalOrders", orderMapper.selectCount(null));
        data.put("totalAmount", orderMapper.sumPaidAmount());
        data.put("todayOrders", orderMapper.selectCount(
                new LambdaQueryWrapper<Order>().ge(Order::getCreateTime, LocalDate.now().atStartOfDay())));
        data.put("todayAmount", orderMapper.sumTodayPaidAmount());
        data.put("pendingPayment", countByStatus(1));
        data.put("pendingShip", countByStatus(2));
        data.put("shipped", countByStatus(3));
        data.put("completed", countByStatus(4));
        data.put("cancelled", countByStatus(5));
        data.put("refunded", countByStatus(6));
        // 待审核退款申请数: 管理端看板红点/待办提醒
        data.put("pendingRefunds", refundMapper.selectCount(
                new LambdaQueryWrapper<Refund>().eq(Refund::getStatus, 1)));
        return data;
    }

    private Long countByStatus(int status) {
        return orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getStatus, status));
    }

    private void checkAdmin(String role) {
        if (!"admin".equals(role)) {
            throw new BusinessException(403, "无管理员权限");
        }
    }
}
