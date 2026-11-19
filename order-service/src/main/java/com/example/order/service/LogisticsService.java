package com.example.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.common.entity.OrderItem;
import com.example.common.entity.OrderLogistics;
import com.example.common.entity.OrderTrace;
import com.example.order.dto.OrderLogisticsVO;
import com.example.order.exception.BusinessException;
import com.example.order.mapper.OrderLogisticsMapper;
import com.example.order.mapper.OrderTraceMapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 物流服务(毕设演示用"轻量模拟快递", 不对接真实快递 API):
 *
 *  1. 发货建档: 商家发货时填写承运商 + 运单号 -> 创建物流单(状态 0-待揽收) + 首条轨迹;
 *  2. 模拟推进: 定时任务每 60 秒扫描"未签收"物流单, 每次推进一个状态并写入一条轨迹:
 *     0-待揽收 -> 1-已揽收 -> 2-运输中 -> 3-派送中 -> 4-已签收, 签收即止。
 *     整程约 4 分钟, 便于答辩演示"发货后轨迹逐步更新"的效果。
 *
 * 并发说明(多实例部署):
 *  - 推进采用"条件更新(状态=旧值)"防并发, 影响行数=0 说明已被其他实例推进, 跳过即可,
 *    不会出现同一单重复推进或重复写轨迹(与 MerchantOrderService 的发货状态机同一套路)。
 *
 * 与订单状态的关系:
 *  - 物流轨迹状态独立演进, 不回写 t_order_item.logistics_status(商家发货/送达)与
 *    t_order.status(订单由用户确认收货才完成), 三者语义互不干扰。
 */
@Service
public class LogisticsService {

    /** 轨迹状态: 待揽收(发货初始) */
    public static final int STATUS_CREATED = 0;
    /** 轨迹状态: 已签收(终态, 到达后不再推进) */
    public static final int STATUS_SIGNED = 4;

    /** 模拟轨迹用的中转城市池(随机取用, 制造"快件在途流转"的观感) */
    private static final List<String> TRANSIT_CITIES = List.of(
            "广州", "深圳", "杭州", "上海", "郑州", "武汉", "成都", "西安", "南京", "济南");

    @Resource
    private OrderLogisticsMapper logisticsMapper;
    @Resource
    private OrderTraceMapper traceMapper;

    // ==================== 发货建档 ====================

    /**
     * 商家发货时创建物流单 + 首条"商家发货"轨迹(在发货事务内调用, 失败整体回滚)。
     * order_item_id 唯一索引兜底防重复发货, 撞唯一键时按业务异常抛出。
     */
    @Transactional
    public void createOnShip(OrderItem item, String company, String trackingNo) {
        OrderLogistics logistics = new OrderLogistics();
        logistics.setOrderItemId(item.getId());
        logistics.setOrderNo(item.getOrderNo());
        logistics.setMerchantId(item.getMerchantId());
        logistics.setCompany(company);
        logistics.setTrackingNo(trackingNo);
        logistics.setStatus(STATUS_CREATED);
        logistics.setLastDesc("商家已发货, 等待" + company + "揽收");
        try {
            logisticsMapper.insert(logistics);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(400, "该商品已存在物流单, 请勿重复发货");
        }
        insertTrace(logistics.getId(), "商家发货",
                "商家已打包发货, 等待" + company + "揽收", null, LocalDateTime.now());
    }

    // ==================== 查询 ====================

    /** 按明细ID查物流单(带轨迹时间线, 升序), 未发货返回 null */
    public OrderLogistics findByItemId(Long orderItemId) {
        OrderLogistics logistics = logisticsMapper.selectOne(
                new LambdaQueryWrapper<OrderLogistics>()
                        .eq(OrderLogistics::getOrderItemId, orderItemId));
        if (logistics != null) {
            fillTraces(List.of(logistics));
        }
        return logistics;
    }

    /** 批量按明细ID查物流单映射(不含轨迹, 列表页填充承运商/运单号用, 一次 IN 避免 N+1) */
    public Map<Long, OrderLogistics> mapByItemIds(Collection<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        return logisticsMapper.selectList(new LambdaQueryWrapper<OrderLogistics>()
                        .in(OrderLogistics::getOrderItemId, itemIds))
                .stream()
                .collect(Collectors.toMap(OrderLogistics::getOrderItemId, l -> l, (a, b) -> a));
    }

    /** 批量填充轨迹时间线(按 trace_time 升序, 一次 IN 查询全部物流单的轨迹) */
    public void fillTraces(List<OrderLogistics> logisticsList) {
        List<Long> ids = logisticsList.stream()
                .filter(l -> l.getTraces() == null)
                .map(OrderLogistics::getId)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, List<OrderTrace>> group = traceMapper.selectList(
                        new LambdaQueryWrapper<OrderTrace>()
                                .in(OrderTrace::getLogisticsId, ids)
                                .orderByAsc(OrderTrace::getTraceTime)
                                .orderByAsc(OrderTrace::getId))
                .stream()
                .collect(Collectors.groupingBy(OrderTrace::getLogisticsId));
        logisticsList.forEach(l -> l.setTraces(group.getOrDefault(l.getId(), List.of())));
    }

    /** 物流单 -> VO(轨迹已在单内时一并转换) */
    public OrderLogisticsVO toVO(OrderLogistics logistics, String productName) {
        OrderLogisticsVO vo = new OrderLogisticsVO();
        vo.setItemId(logistics.getOrderItemId());
        vo.setProductName(productName);
        vo.setCompany(logistics.getCompany());
        vo.setTrackingNo(logistics.getTrackingNo());
        vo.setStatus(logistics.getStatus());
        vo.setLastDesc(logistics.getLastDesc());
        if (logistics.getTraces() != null) {
            vo.setTraces(logistics.getTraces().stream().map(t -> {
                OrderLogisticsVO.TraceVO tv = new OrderLogisticsVO.TraceVO();
                tv.setTitle(t.getTitle());
                tv.setDescription(t.getDescription());
                tv.setLocation(t.getLocation());
                tv.setTraceTime(t.getTraceTime());
                return tv;
            }).collect(Collectors.toList()));
        }
        return vo;
    }

    // ==================== 定时模拟轨迹推进 ====================

    /**
     * 每 60 秒推进一轮: 扫描所有未签收(status < 4)的物流单, 每单推进一个状态并写一条轨迹。
     * initialDelay 30 秒: 错开服务启动高峰。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void advanceTraces() {
        List<OrderLogistics> pending = logisticsMapper.selectList(
                new LambdaQueryWrapper<OrderLogistics>()
                        .lt(OrderLogistics::getStatus, STATUS_SIGNED));
        for (OrderLogistics logistics : pending) {
            advanceOne(logistics);
        }
    }

    /**
     * 单个物流单推进一步: 按当前状态生成下一条轨迹文案 -> 条件更新状态(防并发) -> 成功才写轨迹。
     * 影响行数=0 说明状态已被并发变更(如其他实例已推进), 直接跳过。
     */
    private void advanceOne(OrderLogistics logistics) {
        int from = logistics.getStatus();
        String title;
        String desc;
        String location;
        switch (from) {
            case STATUS_CREATED -> { // 0 -> 1 已揽收
                title = "已揽收";
                desc = logistics.getCompany() + "快递员已揽收快件";
                location = randomCity();
            }
            case 1 -> { // 1 -> 2 运输中
                String city = randomCity();
                title = "运输中";
                desc = "快件到达" + city + "转运中心, 正在中转运输";
                location = city;
            }
            case 2 -> { // 2 -> 3 派送中
                String city = randomCity();
                title = "派送中";
                desc = "快件到达目的地网点, 派送员正在为您派送";
                location = city;
            }
            case 3 -> { // 3 -> 4 已签收
                title = "已签收";
                desc = "快件已签收, 签收人: 本人, 感谢使用" + logistics.getCompany() + ", 期待再次为您服务";
                location = null;
            }
            default -> {
                return; // 未知状态(理论不可达), 防御跳过
            }
        }
        // 条件更新防并发: 仍为旧状态时才推进
        int updated = logisticsMapper.update(null, Wrappers.<OrderLogistics>lambdaUpdate()
                .eq(OrderLogistics::getId, logistics.getId())
                .eq(OrderLogistics::getStatus, from)
                .set(OrderLogistics::getStatus, from + 1)
                .set(OrderLogistics::getLastDesc, desc));
        if (updated == 0) {
            return;
        }
        insertTrace(logistics.getId(), title, desc, location, LocalDateTime.now());
    }

    /** 写一条轨迹(轨迹时间 = 推进时刻) */
    private void insertTrace(Long logisticsId, String title, String desc, String location,
                             LocalDateTime traceTime) {
        OrderTrace trace = new OrderTrace();
        trace.setLogisticsId(logisticsId);
        trace.setTitle(title);
        trace.setDescription(desc);
        trace.setLocation(location);
        trace.setTraceTime(traceTime);
        traceMapper.insert(trace);
    }

    /** 随机取一个模拟中转城市 */
    private String randomCity() {
        return TRANSIT_CITIES.get(ThreadLocalRandom.current().nextInt(TRANSIT_CITIES.size()));
    }
}
