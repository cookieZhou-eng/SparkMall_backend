package com.example.order.controller;

import com.example.common.result.Result;
import com.example.order.dto.MerchantSoldOrderVO;
import com.example.order.dto.MerchantStatsVO;
import com.example.order.service.MerchantOrderService;
import jakarta.annotation.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 商家卖出订单控制器(薄控制器): 网关 /order/merchant/** 路由转发过来。
 *
 * 分层说明:
 *  - 只做参数接收与 Result 封装, 角色/归属/状态校验与业务逻辑在 MerchantOrderService。
 *  - 网关已校验 JWT 且 role=merchant, 并透传 X-User-Id / X-User-Role;
 *    Service 内再做一次角色校验(双保险), 防止绕过网关直连。
 */
@RestController
@RequestMapping("/order/merchant")
public class MerchantOrderController {

    @Resource
    private MerchantOrderService merchantOrderService;

    /**
     * 我卖出的订单(按明细分页, 同一订单多个属于当前商家的明细合并为一个订单组)
     * @param logisticsStatus 可选, 按物流状态过滤: 0-待发货 1-已发货 2-已送达
     * @param startTime 可选, 下单时间起(格式 yyyy-MM-dd HH:mm:ss)
     * @param endTime 可选, 下单时间止(格式 yyyy-MM-dd HH:mm:ss)
     */
    @GetMapping("/sold")
    public Result<Result.PageData<MerchantSoldOrderVO>> sold(
            @RequestHeader("X-User-Id") Long merchantId,
            @RequestHeader("X-User-Role") String role,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Integer logisticsStatus,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return Result.success(merchantOrderService.soldOrders(
                merchantId, role, page, size, logisticsStatus, startTime, endTime));
    }

    /**
     * 发货: 明细 logistics_status 0->1, 写 ship_time
     * 校验: 明细属于当前商家 + 待发货 + 订单已支付
     */
    @PutMapping("/item/{itemId}/ship")
    public Result<Void> ship(
            @RequestHeader("X-User-Id") Long merchantId,
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long itemId) {
        merchantOrderService.ship(merchantId, role, itemId);
        return Result.success();
    }

    /**
     * 确认送达: 明细 logistics_status 1->2, 写 delivery_time
     * 校验: 明细属于当前商家 + 已发货
     */
    @PutMapping("/item/{itemId}/deliver")
    public Result<Void> deliver(
            @RequestHeader("X-User-Id") Long merchantId,
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long itemId) {
        merchantOrderService.deliver(merchantId, role, itemId);
        return Result.success();
    }

    /**
     * 商家经营统计: 总销售额 / 卖出件数 / 待发货明细数 / 已支付订单数
     */
    @GetMapping("/stats")
    public Result<MerchantStatsVO> stats(
            @RequestHeader("X-User-Id") Long merchantId,
            @RequestHeader("X-User-Role") String role) {
        return Result.success(merchantOrderService.stats(merchantId, role));
    }
}
