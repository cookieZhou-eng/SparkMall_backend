package com.example.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.entity.Order;
import com.example.common.entity.OrderItem;
import com.example.common.result.Result;
import com.example.order.dto.MerchantSoldOrderVO;
import com.example.order.dto.MerchantStatsVO;
import com.example.order.exception.BusinessException;
import com.example.order.mapper.OrderItemMapper;
import com.example.order.mapper.OrderMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商家卖出订单服务: "我卖出的订单"分页 / 发货 / 确认送达 / 经营统计。
 *
 * 分层说明:
 *  - 网关 /order/merchant/** 路由已校验 JWT 且 role=merchant 并透传 X-User-Id/X-User-Role,
 *    本 Service 仍对 role 做二次校验(双保险, 防止直连绕过网关), 与 MerchantProductService 一致。
 *  - 物流流转以"订单明细"为单位: 同一订单可含多个商家的商品, 各商家的明细独立发货/送达。
 *  - 状态机: logistics_status 0(待发货) -> 1(已发货, 写 ship_time) -> 2(已送达, 写 delivery_time),
 *    发货前要求订单已支付(status=2, 现有支付流程支付成功后固定置为 2);
 *    订单所有明细均发货后订单 status 2->3(已发货/待收货), 所有明细送达后仍保持 3,
 *    由用户确认收货(/order/confirm)才置为 4(已完成)。
 *  - 更新用"条件更新(logistics_status=旧值)"防并发, 影响行数=0 说明状态已被并发变更。
 */
@Service
public class MerchantOrderService {

    /** 订单已支付的状态集合(现有代码只会把支付后的订单置为 2; 3/4 为预留给发货/签收的语义) */
    private static final List<Integer> PAID_STATUS = List.of(2, 3, 4);

    @Resource
    private OrderMapper orderMapper;
    @Resource
    private OrderItemMapper orderItemMapper;

    /**
     * 我卖出的订单(按明细分页, 同一订单多个属于当前商家的明细合并为一个订单组)。
     *
     * 实现步骤:
     *  1. 分页查 t_order_item 中 merchant_id = 当前商家的明细(可选按物流状态过滤), 时间倒序;
     *  2. 收集本页明细的 orderNo 批量查 t_order(一次 IN, 避免 N+1);
     *  3. 按 orderNo 分组合并成 VO(orderNo 用 LinkedHashMap 保持时间倒序)。
     * total 为明细条数(非订单组数), 与前端 el-pagination 对齐。
     */
    public Result.PageData<MerchantSoldOrderVO> soldOrders(Long merchantId, String role,
                                                           Integer page, Integer size,
                                                           Integer logisticsStatus) {
        requireMerchant(role);
        Page<OrderItem> pageResult = orderItemMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getMerchantId, merchantId)
                        .eq(logisticsStatus != null, OrderItem::getLogisticsStatus, logisticsStatus)
                        .orderByDesc(OrderItem::getCreateTime)
                        .orderByDesc(OrderItem::getId));
        List<OrderItem> items = pageResult.getRecords();
        if (items.isEmpty()) {
            return new Result.PageData<>(pageResult.getTotal(), List.of());
        }

        // 同一订单的多个明细合并为一组(LinkedHashMap 保持明细的查询顺序)
        Map<String, List<OrderItem>> groupByOrderNo = items.stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderNo,
                        LinkedHashMap::new, Collectors.toList()));

        // 一次 IN 批量查这些明细所属的订单主表(取支付状态/收货人信息)
        Map<String, Order> orderMap = orderMapper.selectList(
                        new LambdaQueryWrapper<Order>().in(Order::getOrderNo, groupByOrderNo.keySet()))
                .stream()
                .collect(Collectors.toMap(Order::getOrderNo, o -> o));

        List<MerchantSoldOrderVO> records = new ArrayList<>();
        for (Map.Entry<String, List<OrderItem>> entry : groupByOrderNo.entrySet()) {
            Order order = orderMap.get(entry.getKey());
            if (order == null) {
                continue; // 订单主表不存在(理论不可达), 跳过防御
            }
            records.add(toSoldOrderVO(order, entry.getValue()));
        }
        return new Result.PageData<>(pageResult.getTotal(), records);
    }

    /**
     * 发货: 校验明细归属 + 待发货状态 + 订单已支付 -> logistics_status 0->1, 写 ship_time。
     * 发货成功后若该订单所有明细均已发货(logistics_status >= 1), 把订单状态 2(已支付/待发货) 条件更新为 3(已发货/待收货)。
     */
    @Transactional
    public void ship(Long merchantId, String role, Long itemId) {
        requireMerchant(role);
        OrderItem item = requireOwnedItem(merchantId, itemId);
        if (item.getLogisticsStatus() == null || item.getLogisticsStatus() != 0) {
            throw new BusinessException(400, "该商品当前不是待发货状态, 无法发货");
        }
        Order order = requireOrderByNo(item.getOrderNo());
        if (!PAID_STATUS.contains(order.getStatus())) {
            throw new BusinessException(400, "订单尚未支付, 暂不可发货");
        }
        // 条件更新防并发: 仍为待发货(0)时才置为已发货(1)
        int updated = orderItemMapper.update(null, Wrappers.<OrderItem>lambdaUpdate()
                .eq(OrderItem::getId, itemId)
                .eq(OrderItem::getLogisticsStatus, 0)
                .set(OrderItem::getLogisticsStatus, 1)
                .set(OrderItem::getShipTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(400, "发货失败, 商品状态已变更, 请刷新后重试");
        }
        // 所有明细都已发货(>=1) 时, 订单整体进入"已发货/待收货"状态
        promoteOrderToShipped(item.getOrderNo());
    }

    /**
     * 确认送达: 校验明细归属 + 已发货状态 -> logistics_status 1->2, 写 delivery_time。
     * 送达后订单仍保持 status=3(已发货/待收货), 需用户调用 /order/confirm 才置为 4(已完成), 不自动完成。
     */
    @Transactional
    public void deliver(Long merchantId, String role, Long itemId) {
        requireMerchant(role);
        OrderItem item = requireOwnedItem(merchantId, itemId);
        if (item.getLogisticsStatus() == null || item.getLogisticsStatus() != 1) {
            throw new BusinessException(400, "该商品当前不是已发货状态, 无法确认送达");
        }
        // 条件更新防并发: 仍为已发货(1)时才置为已送达(2)
        int updated = orderItemMapper.update(null, Wrappers.<OrderItem>lambdaUpdate()
                .eq(OrderItem::getId, itemId)
                .eq(OrderItem::getLogisticsStatus, 1)
                .set(OrderItem::getLogisticsStatus, 2)
                .set(OrderItem::getDeliveryTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(400, "确认送达失败, 商品状态已变更, 请刷新后重试");
        }
        // 即使全部明细都已送达也保持 status=3, 由用户确认收货后置为 4
    }

    /**
     * 商家经营统计(总销售额/卖出件数/待发货明细数/已支付订单数)。
     * 单条 JOIN 聚合 SQL 完成, 见 OrderItemMapper.merchantStats。
     */
    public MerchantStatsVO stats(Long merchantId, String role) {
        requireMerchant(role);
        return orderItemMapper.merchantStats(merchantId);
    }

    // ==================== 私有辅助 ====================

    /** 角色校验(业务侧双保险): 必须 merchant */
    private void requireMerchant(String role) {
        if (!"merchant".equals(role)) {
            throw new BusinessException(403, "无商家权限");
        }
    }

    /** 校验明细存在且属于当前商家, 否则 403 */
    private OrderItem requireOwnedItem(Long merchantId, Long itemId) {
        if (itemId == null) {
            throw new BusinessException(400, "明细 id 不能为空");
        }
        OrderItem item = orderItemMapper.selectById(itemId);
        if (item == null || !merchantId.equals(item.getMerchantId())) {
            throw new BusinessException(403, "无权操作该订单明细");
        }
        return item;
    }

    /** 按订单号查订单, 不存在抛业务异常 */
    private Order requireOrderByNo(String orderNo) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(400, "订单不存在");
        }
        return order;
    }

    /**
     * 订单所有明细都已发货(logistics_status >= 1)时, 把订单状态 2(已支付/待发货) 条件更新为
     * 3(已发货/待收货); 尚有未发货明细或订单已非 2 状态则不更新。
     */
    private void promoteOrderToShipped(String orderNo) {
        Long pending = orderItemMapper.selectCount(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderNo, orderNo)
                .lt(OrderItem::getLogisticsStatus, 1));
        if (pending != null && pending > 0) {
            return;
        }
        orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getOrderNo, orderNo)
                .eq(Order::getStatus, 2)
                .set(Order::getStatus, 3));
    }

    /** 订单 + 当前商家的明细列表 -> 卖出订单 VO(订单级字段从 t_order 取, 明细转 VO) */
    private MerchantSoldOrderVO toSoldOrderVO(Order order, List<OrderItem> items) {
        MerchantSoldOrderVO vo = new MerchantSoldOrderVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setCreateTime(order.getCreateTime());
        vo.setOrderStatus(order.getStatus());
        vo.setPayTime(order.getPayTime());
        vo.setReceiverName(order.getReceiverName());
        vo.setReceiverPhone(order.getReceiverPhone());
        vo.setReceiverAddr(order.getReceiverAddr());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setItems(items.stream().map(this::toSoldItemVO).collect(Collectors.toList()));
        return vo;
    }

    private MerchantSoldOrderVO.MerchantSoldItemVO toSoldItemVO(OrderItem item) {
        MerchantSoldOrderVO.MerchantSoldItemVO vo = new MerchantSoldOrderVO.MerchantSoldItemVO();
        vo.setItemId(item.getId());
        vo.setProductId(item.getProductId());
        vo.setProductName(item.getProductName());
        vo.setProductImage(item.getProductImage());
        vo.setPrice(item.getPrice());
        vo.setCount(item.getCount());
        vo.setSubtotal(item.getSubtotal());
        vo.setLogisticsStatus(item.getLogisticsStatus());
        vo.setShipTime(item.getShipTime());
        vo.setDeliveryTime(item.getDeliveryTime());
        return vo;
    }
}
