package com.example.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.dto.StockDeductRequest;
import com.example.common.entity.Order;
import com.example.common.entity.OrderItem;
import com.example.common.entity.Payment;
import com.example.common.entity.Refund;
import com.example.common.result.Result;
import com.example.order.dto.RefundApplyRequest;
import com.example.order.dto.RefundAuditRequest;
import com.example.order.exception.BusinessException;
import com.example.order.feign.ProductFeignClient;
import com.example.order.mapper.OrderItemMapper;
import com.example.order.mapper.OrderMapper;
import com.example.order.mapper.PaymentMapper;
import com.example.order.mapper.RefundMapper;
import jakarta.annotation.Resource;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 退款售后业务。
 *
 * 用户侧:
 *  - 已支付(2)/已发货(3)/已完成(4)的订单可申请退款;
 *  - 同一订单同时只允许一个进行中的申请(status 1/2/4)。
 *
 * 管理端:
 *  - 拒绝: 申请单置为 3(已拒绝), 仅记录审核备注;
 *  - 通过: 退款单置为 5(已退款), 订单置为 6(已退款), 支付流水置为 3(已退款),
 *    并通过 product-service 回补库存/回退销量。跨服务操作由 Seata AT 保证一致性。
 */
@Service
public class RefundService {

    /** 退款单状态 */
    private static final int STATUS_PENDING = 1;
    private static final int STATUS_REJECTED = 3;
    private static final int STATUS_REFUNDED = 5;

    /** 订单允许申请退款的状态: 已支付/待发货、已发货/待收货、已完成 */
    private static final List<Integer> REFUNDABLE_ORDER_STATUS = List.of(2, 3, 4);
    /** 进行中的退款状态: 待审核/已通过/退款中 */
    private static final List<Integer> ACTIVE_REFUND_STATUS = List.of(1, 2, 4);

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_IMAGES = 3;

    private static final DateTimeFormatter NO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    @Resource
    private RefundMapper refundMapper;
    @Resource
    private OrderMapper orderMapper;
    @Resource
    private OrderItemMapper orderItemMapper;
    @Resource
    private PaymentMapper paymentMapper;
    @Resource
    private ProductFeignClient productFeignClient;

    /**
     * 用户申请退款
     */
    public Refund apply(Long userId, RefundApplyRequest req) {
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        if (req == null || req.getOrderId() == null) {
            throw new BusinessException(400, "订单ID不能为空");
        }
        if (req.getType() == null || req.getType() < 1 || req.getType() > 2) {
            throw new BusinessException(400, "退款类型不合法");
        }
        String reason = req.getReason() == null ? "" : req.getReason().trim();
        if (reason.length() > 255) {
            throw new BusinessException(400, "退款原因不能超过 255 字");
        }
        String description = req.getDescription() == null ? "" : req.getDescription().trim();
        if (description.length() > 500) {
            throw new BusinessException(400, "问题描述不能超过 500 字");
        }
        String images = joinImages(req.getImages());

        Order order = orderMapper.selectById(req.getOrderId());
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(403, "无权操作该订单");
        }
        if (order.getStatus() == null || !REFUNDABLE_ORDER_STATUS.contains(order.getStatus())) {
            throw new BusinessException(400, "当前订单状态不可申请退款");
        }
        Long activeCount = refundMapper.selectCount(new LambdaQueryWrapper<Refund>()
                .eq(Refund::getOrderId, order.getId())
                .in(Refund::getStatus, ACTIVE_REFUND_STATUS));
        if (activeCount != null && activeCount > 0) {
            throw new BusinessException(400, "该订单已有进行中的退款申请, 请勿重复提交");
        }

        Refund refund = new Refund();
        refund.setRefundNo("RF" + LocalDateTime.now().format(NO_FORMATTER)
                + ThreadLocalRandom.current().nextInt(1000, 9999));
        refund.setOrderId(order.getId());
        refund.setOrderNo(order.getOrderNo());
        refund.setUserId(userId);
        refund.setAmount(order.getTotalAmount());
        refund.setType(req.getType());
        refund.setReason(reason.isEmpty() ? null : reason);
        refund.setDescription(description.isEmpty() ? null : description);
        refund.setImages(images);
        refund.setStatus(STATUS_PENDING);
        refund.setApplyTime(LocalDateTime.now());
        refundMapper.insert(refund);
        return refund;
    }

    /**
     * 我的退款申请分页
     */
    public Result.PageData<Refund> myList(Long userId, Integer page, Integer size) {
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        Page<Refund> pageResult = refundMapper.selectPage(new Page<>(p, s),
                new LambdaQueryWrapper<Refund>()
                        .eq(Refund::getUserId, userId)
                        .orderByDesc(Refund::getApplyTime));
        return new Result.PageData<>(pageResult.getTotal(), pageResult.getRecords());
    }

    /**
     * 查询某个订单的退款申请记录(本人)
     */
    public List<Refund> listByOrder(Long orderId, Long userId) {
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
            throw new BusinessException(403, "无权查看该订单退款记录");
        }
        return refundMapper.selectList(new LambdaQueryWrapper<Refund>()
                .eq(Refund::getOrderId, orderId)
                .orderByDesc(Refund::getApplyTime));
    }

    /**
     * 管理端退款申请分页(支持状态/退款单号筛选)
     */
    public Result.PageData<Refund> adminList(String role, Integer status, String refundNo,
                                             Integer page, Integer size) {
        checkAdmin(role);
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        LambdaQueryWrapper<Refund> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(Refund::getStatus, status);
        }
        if (refundNo != null && !refundNo.isBlank()) {
            wrapper.like(Refund::getRefundNo, refundNo.trim());
        }
        wrapper.orderByDesc(Refund::getApplyTime);
        Page<Refund> pageResult = refundMapper.selectPage(new Page<>(p, s), wrapper);
        return new Result.PageData<>(pageResult.getTotal(), pageResult.getRecords());
    }

    /**
     * 管理端退款详情
     */
    public Refund adminDetail(String role, Long id) {
        checkAdmin(role);
        Refund refund = refundMapper.selectById(id);
        if (refund == null) {
            throw new BusinessException(404, "退款申请不存在");
        }
        return refund;
    }

    /**
     * 管理端审核退款申请。
     *
     * 通过时是典型的跨服务分布式事务:
     *   退款单落状态 + 订单置为已退款 + 支付流水置为已退款(本地 DB) + 回补商品库存(Feign 远程)。
     * 任意一步失败整体回滚, 避免出现"库存补了但订单还是已完成"的不一致。
     */
    @GlobalTransactional(name = "refund-audit", rollbackFor = Exception.class)
    @Transactional
    public void audit(String role, Long id, RefundAuditRequest req) {
        checkAdmin(role);
        doAudit(requireRefund(id), req);
    }

    /**
     * 商家端退款申请分页(只包含当前商家订单的退款)。
     *
     * 商家归属判定: t_refund 无 merchant_id, 通过 t_order_item.merchant_id 反查,
     * 先取该商家所有订单 id 再 IN 过滤退款单(订单按商家拆单, 一单只属于一个商家)。
     */
    public Result.PageData<Refund> merchantList(Long merchantId, String role, Integer status,
                                                String refundNo, Integer page, Integer size) {
        requireMerchant(role);
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        List<Long> orderIds = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .select(OrderItem::getOrderId)
                        .eq(OrderItem::getMerchantId, merchantId))
                .stream()
                .map(OrderItem::getOrderId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (orderIds.isEmpty()) {
            return new Result.PageData<>(0L, List.of());
        }
        LambdaQueryWrapper<Refund> wrapper = new LambdaQueryWrapper<Refund>()
                .in(Refund::getOrderId, orderIds);
        if (status != null) {
            wrapper.eq(Refund::getStatus, status);
        }
        if (refundNo != null && !refundNo.isBlank()) {
            wrapper.like(Refund::getRefundNo, refundNo.trim());
        }
        wrapper.orderByDesc(Refund::getApplyTime);
        Page<Refund> pageResult = refundMapper.selectPage(new Page<>(p, s), wrapper);
        return new Result.PageData<>(pageResult.getTotal(), pageResult.getRecords());
    }

    /**
     * 商家端退款详情(校验退款单所属订单含当前商家的商品)
     */
    public Refund merchantDetail(Long merchantId, String role, Long id) {
        requireMerchant(role);
        return requireMerchantRefund(merchantId, id);
    }

    /**
     * 商家审核退款申请: 校验归属后复用与管理端一致的审核逻辑。
     * 通过时跨服务回补库存同样由 Seata AT 保证一致性。
     */
    @GlobalTransactional(name = "refund-audit-merchant", rollbackFor = Exception.class)
    @Transactional
    public void merchantAudit(Long merchantId, String role, Long id, RefundAuditRequest req) {
        requireMerchant(role);
        doAudit(requireMerchantRefund(merchantId, id), req);
    }

    /**
     * 审核逻辑(管理端/商家端共用): 拒绝仅改状态; 通过则退款单/订单/支付流水落库并回补库存。
     * 抽出私有方法是为了让两个 @GlobalTransactional 入口都能各自被代理生效(同类内部调用不走代理)。
     */
    private void doAudit(Refund refund, RefundAuditRequest req) {
        if (req == null || req.getApproved() == null) {
            throw new BusinessException(400, "请选择审核结果");
        }
        if (refund.getStatus() == null || refund.getStatus() != STATUS_PENDING) {
            throw new BusinessException(400, "该退款申请已处理, 请勿重复审核");
        }
        String remark = req.getRemark() == null ? "" : req.getRemark().trim();
        if (remark.length() > 255) {
            throw new BusinessException(400, "审核备注不能超过 255 字");
        }
        LocalDateTime now = LocalDateTime.now();

        if (!Boolean.TRUE.equals(req.getApproved())) {
            int updated = refundMapper.update(null, Wrappers.<Refund>lambdaUpdate()
                    .eq(Refund::getId, refund.getId())
                    .eq(Refund::getStatus, STATUS_PENDING)
                    .set(Refund::getStatus, STATUS_REJECTED)
                    .set(Refund::getAuditRemark, remark.isEmpty() ? null : remark)
                    .set(Refund::getAuditTime, now));
            if (updated == 0) {
                throw new BusinessException(400, "审核失败, 退款状态已变更, 请刷新后重试");
            }
            return;
        }

        // 审核通过: 校验订单状态仍可退款
        Order order = orderMapper.selectById(refund.getOrderId());
        if (order == null) {
            throw new BusinessException(404, "关联订单不存在");
        }
        if (order.getStatus() == null || !REFUNDABLE_ORDER_STATUS.contains(order.getStatus())) {
            throw new BusinessException(400, "订单当前状态不可退款(可能已取消或已退款)");
        }

        // 1) 退款单 -> 已退款
        int refundUpdated = refundMapper.update(null, Wrappers.<Refund>lambdaUpdate()
                .eq(Refund::getId, refund.getId())
                .eq(Refund::getStatus, STATUS_PENDING)
                .set(Refund::getStatus, STATUS_REFUNDED)
                .set(Refund::getAuditRemark, remark.isEmpty() ? null : remark)
                .set(Refund::getAuditTime, now)
                .set(Refund::getRefundTime, now));
        if (refundUpdated == 0) {
            throw new BusinessException(400, "退款失败, 退款状态已变更, 请刷新后重试");
        }

        // 2) 订单 -> 6 已退款(条件更新防并发)
        int orderUpdated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .in(Order::getStatus, REFUNDABLE_ORDER_STATUS)
                .set(Order::getStatus, 6));
        if (orderUpdated == 0) {
            throw new BusinessException(400, "退款失败, 订单状态已变更, 请刷新后重试");
        }

        // 3) 支付流水 -> 3 已退款(只更新支付成功的那条)
        paymentMapper.update(null, Wrappers.<Payment>lambdaUpdate()
                .eq(Payment::getOrderNo, order.getOrderNo())
                .eq(Payment::getStatus, 1)
                .set(Payment::getStatus, 3)
                .set(Payment::getUpdateTime, now));

        // 4) 回补库存/回退销量(远程, 失败抛异常触发 Seata 回滚)
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
        for (OrderItem item : items) {
            Result<Boolean> restored = productFeignClient.restoreStock(
                    new StockDeductRequest(item.getProductId(), item.getCount()));
            if (restored == null || restored.getCode() == null || restored.getCode() != 200
                    || !Boolean.TRUE.equals(restored.getData())) {
                throw new BusinessException(500, "退款处理失败, 请稍后重试");
            }
        }
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

    private void checkAdmin(String role) {
        if (role == null || !"admin".equals(role)) {
            throw new BusinessException(403, "无管理员权限");
        }
    }

    /** 角色校验(业务侧双保险): 必须 merchant */
    private void requireMerchant(String role) {
        if (!"merchant".equals(role)) {
            throw new BusinessException(403, "无商家权限");
        }
    }

    /** 按 id 查退款单, 不存在抛 404 */
    private Refund requireRefund(Long id) {
        if (id == null) {
            throw new BusinessException(400, "退款申请ID不能为空");
        }
        Refund refund = refundMapper.selectById(id);
        if (refund == null) {
            throw new BusinessException(404, "退款申请不存在");
        }
        return refund;
    }

    /**
     * 校验退款单所属订单包含当前商家的商品明细(订单按商家拆单, 一单只属于一个商家),
     * 不是自己的退款申请一律 403, 防止商家审核/查看其他商家的退款。
     */
    private Refund requireMerchantRefund(Long merchantId, Long id) {
        if (merchantId == null) {
            throw new BusinessException(401, "未登录");
        }
        Refund refund = requireRefund(id);
        Long owned = orderItemMapper.selectCount(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, refund.getOrderId())
                .eq(OrderItem::getMerchantId, merchantId));
        if (owned == null || owned == 0) {
            throw new BusinessException(403, "无权操作该退款申请");
        }
        return refund;
    }
}
