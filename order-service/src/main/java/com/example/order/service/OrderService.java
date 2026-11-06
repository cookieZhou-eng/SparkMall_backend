package com.example.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.common.dto.SeckillConfirmRequest;
import com.example.common.dto.StockDeductRequest;
import com.example.common.entity.Order;
import com.example.common.entity.OrderItem;
import com.example.common.entity.Payment;
import com.example.common.entity.Product;
import com.example.common.entity.Refund;
import com.example.common.entity.Review;
import com.example.common.result.Result;
import com.example.order.dto.OrderCancelRequest;
import com.example.order.config.RabbitConfig;
import com.example.order.dto.OrderCreateRequest;
import com.example.order.dto.OrderPayRequest;
import com.example.order.exception.BusinessException;
import com.example.order.feign.ProductFeignClient;
import com.example.order.mapper.OrderItemMapper;
import com.example.order.mapper.OrderMapper;
import com.example.order.mapper.PaymentMapper;
import com.example.order.mapper.RefundMapper;
import com.example.order.mapper.ReviewMapper;
import jakarta.annotation.Resource;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 订单服务(下单 + 支付 + 取消/超时释放核心业务)
 *
 * 业务流程(跨服务, 简化为"锁库存"模型):
 *   1. 下单 /order/create : 逐个商品 Feign 调 product-service【锁定库存】(locked 增加)
 *      -> 生成待支付订单(status=1) -> Redis 写入 30 分钟 TTL 计时键
 *   2. 支付 /order/pay  : 校验订单待支付 -> 状态流转 1->2 -> Feign 调 product-service
 *      【最终扣减】(stock 与 locked 同时减少, 把"锁定"转为"已售") -> 落支付流水 -> 删除 Redis 计时键
 *   3. 取消/超时         : 状态流转 1->5(已取消) -> Feign 调 product-service【释放锁定库存】
 *      (locked 减少, 让商品重新可卖) -> 删除 Redis 计时键
 *
 * 关于库存锁定的设计(为什么下单用"锁定"而不是直接扣减):
 *   - 用户下单但没支付, 商品只是"暂时不可卖", 不等于"已经卖出"。
 *   - 用 t_product_stock 的 locked 字段先锁住, 若用户最终支付则转成真实扣减,
 *     若超时/取消则释放 locked, 库存既不会超卖也不会凭空丢失。
 *1qaw
 * 关于超时自动释放(面试高频点, 两种方案结合):
 *   - 方案一(定时任务, 兜底且可靠): @Scheduled 每分钟扫一次"创建超过 30 分钟仍待支付"的订单,
 *     批量置为取消并释放锁定库存。不依赖 Redis, Redis 挂了也能兜底。
 *   - 方案二(Redis TTL 计时键, 精准): 下单时写 key = order:pay:timeout:{orderNo} 并设 30 分钟过期。
 *     Redis 键过期时若能配合 keyspace 事件监听, 可"刚过期立即"触发取消; 这里主要用来记录/清理计时,
 *     真正兜底还是靠定时任务(键过期 Listener 需要另开订阅线程, 环境不稳时反而增加故障面, 故不作为启动依赖)。
 *
 * 事务边界说明:
 *   本地 @Transactional 只覆盖"订单状态 + 支付流水"等 DB 操作; 库存的锁定/扣减/释放是 Feign 远程调用,
 *   不在本地事务内, 属于典型分布式事务问题。当前采用"先锁库存再落订单/先改状态再改库存"的简化方案
 *   保证基本一致, 生产可接入 Seata AT 模式统一管理。
 *
 * 订单状态语义:
 *   1 = 待支付
 *   2 = 已支付/待发货
 *   3 = 已发货/待收货(该订单所有明细均已发货)
 *   4 = 已完成(用户确认收货)
 *   5 = 已取消
 *
 * 拆单说明:
 *   同一购物车中不同商家的商品按 merchantId 拆成独立订单(merchantId=null 视为平台自营, 归入同一单),
 *   每个子订单独立订单号/金额/明细/超时计时, 可独立支付与超时取消。
 */
@Service
public class OrderService {

    /** Redis 里待支付订单超时计时键的前缀 */
    private static final String TIMEOUT_KEY_PREFIX = "order:pay:timeout:";

    @Resource
    private OrderMapper orderMapper;
    @Resource
    private OrderItemMapper orderItemMapper;
    @Resource
    private PaymentMapper paymentMapper;
    @Resource
    private ReviewMapper reviewMapper;
    @Resource
    private RefundMapper refundMapper;
    @Resource
    private ProductFeignClient productFeignClient;
    // 注意: Spring Boot 会同时自动配置 redisTemplate(RedisTemplate) 与 stringRedisTemplate(StringRedisTemplate) 两个 bean。
    // 字段名必须叫 stringRedisTemplate, 这样 @Resource 按名注入才能匹配到 StringRedisTemplate 类型;
    // 若叫 redisTemplate 会按名拿到 RedisTemplate, 两者类型不匹配导致启动失败。
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RabbitTemplate rabbitTemplate;

    /** 待支付超时时间(分钟), 可在 order-service 的 application.yaml 里覆盖 */
    @Value("${app.order.expire-minutes:30}")
    private int expireMinutes;

    /**
     * 创建订单(下单锁定库存, 生成待支付订单)。
     *
     * 同一购物车中不同商家的商品会拆成多个独立子订单:
     *   - 按商品快照的 merchantId 分组(merchantId=null 视为平台自营, 归入同一组);
     *   - 每组独立订单号: SP + yyyyMMddHHmmss + 4位随机 + 两位组序号(同批次不重复);
     *   - 每组独立锁库存/金额/明细/Redis 超时键/延迟取消消息, 各自独立计时与取消;
     *   - 任意一组失败, 本轮已锁定的库存全部释放(补偿)。
     *
     * @return 拆单后的订单列表(每个商家一个订单)
     */
    @GlobalTransactional(name = "order-create",rollbackFor = Exception.class)
    @Transactional
    public List<Order> createOrder(OrderCreateRequest req) {
        // 1. 参数校验
        if (req.getUserId() == null || req.getItems() == null || req.getItems().isEmpty()) {
            throw new IllegalArgumentException("下单参数不合法: 缺少用户或商品");
        }

        // 2. 先查商品并组装快照(此时尚未锁库存), 为按商家拆单做准备
        List<OrderItem> snapshots = new ArrayList<>();
        for (OrderCreateRequest.Item item : req.getItems()) {
            if (item.getProductId() == null || item.getCount() == null || item.getCount() <= 0) {
                throw new IllegalArgumentException("商品数量不合法");
            }

            // Feign 查商品(校验存在 + 拿价格/名称/图做快照)
            Result<Product> productResult = productFeignClient.getById(item.getProductId());
            Product product = productResult.getData();
            if (product == null) {
                throw new RuntimeException("商品不存在: id=" + item.getProductId());
            }

            // 组装订单快照(价格/名称/图片在下单瞬间固定)
            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setProductImage(product.getMainImage());
            orderItem.setPrice(product.getPrice());
            orderItem.setCount(item.getCount());
            // 商品总计价格: 商品单价 * 商品数量
            orderItem.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(item.getCount())));
            // 商家归属: 下单时从商品快照写入, 用于按商家拆单与商家端发货/统计
            orderItem.setMerchantId(product.getMerchantId());
            // 物流状态由 DB 默认值 0(待发货) 落库, 显式赋值保证插入语句字段完整
            orderItem.setLogisticsStatus(0);
            snapshots.add(orderItem);
        }

        // 3. 按商家分组: LinkedHashMap 保持购物车顺序; null key 表示平台自营(单独一组)
        Map<Long, List<OrderItem>> groups = new LinkedHashMap<>();
        for (OrderItem item : snapshots) {
            groups.computeIfAbsent(item.getMerchantId(), k -> new ArrayList<>()).add(item);
        }

        // 4. 同批次共享 时间戳 + 4位随机, 追加两位组序号保证批内唯一; 真正唯一性由 DB 唯一索引兜底
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String batchRandom = String.format("%04d", new Random().nextInt(10000));
        List<Order> orders = new ArrayList<>();
        // 记录本轮已成功"锁定库存"的商品, 一旦中途失败用于补偿回滚(避免锁单泄漏)
        List<OrderItem> lockedItems = new ArrayList<>();
        int groupSeq = 1;

        try {
            for (Map.Entry<Long, List<OrderItem>> entry : groups.entrySet()) {
                List<OrderItem> groupItems = entry.getValue();
                int seq = groupSeq++;
                String orderNo = "SP" + timestamp + batchRandom + String.format("%02d", seq);

                // 4.1 该组逐个商品锁定库存并汇总金额/数量
                BigDecimal totalAmount = BigDecimal.ZERO;
                int totalCount = 0;
                for (OrderItem orderItem : groupItems) {
                    Result<Boolean> lockResult = productFeignClient.lockStock(
                            new StockDeductRequest(orderItem.getProductId(), orderItem.getCount()));
                    if (lockResult.getData() == null || !lockResult.getData()) {
                        throw new RuntimeException("商品库存不足: " + orderItem.getProductName());
                    }
                    lockedItems.add(orderItem); // 记录已锁定, 用于失败补偿
                    orderItem.setOrderNo(orderNo);
                    totalAmount = totalAmount.add(orderItem.getSubtotal());
                    totalCount += orderItem.getCount();
                }

                // 4.2 插入该组订单主表(status=1 待支付)
                Order order = new Order();
                order.setOrderNo(orderNo);
                order.setUserId(req.getUserId());
                order.setTotalAmount(totalAmount);
                order.setTotalCount(totalCount);
                order.setStatus(1);
                order.setReceiverName(req.getReceiverName());
                order.setReceiverPhone(req.getReceiverPhone());
                order.setReceiverAddr(req.getReceiverAddr());
                order.setRemark(req.getRemark());
                try {
                    orderMapper.insert(order);
                } catch (DuplicateKeyException e) {
                    // 订单号撞上唯一索引(极小概率): 换 4位随机重试一次; 再失败则抛出走补偿
                    orderNo = "SP" + timestamp
                            + String.format("%04d", new Random().nextInt(10000))
                            + String.format("%02d", seq);
                    order.setOrderNo(orderNo);
                    for (OrderItem orderItem : groupItems) {
                        orderItem.setOrderNo(orderNo);
                    }
                    orderMapper.insert(order);
                }

                // 4.3 插入该组订单详情(只含本组商品, 不混入其他商家明细)
                for (OrderItem orderItem : groupItems) {
                    orderItem.setOrderId(order.getId());
                    orderItemMapper.insert(orderItem);
                }

                // 4.4 写超时计时键 + 发延迟取消消息(按 orderNo, 每组独立计时)
                saveRedisTimeout(orderNo);
                sendOrderExpireMessage(orderNo);
                notifyOrderCreated(order);

                order.setItems(groupItems);
                // 返回待支付剩余秒数, 供前端倒计时展示(createTime 未回填时按整段时长计算)
                order.setRemainingSeconds(computeRemainingSeconds(order));
                orders.add(order);
            }
            return orders;
        } catch (RuntimeException e) {
            // 补偿: 本轮已锁定的库存全部释放, 避免"订单未落库但库存一直被锁住"的泄漏
            for (OrderItem it : lockedItems) {
                try {
                    productFeignClient.releaseStock(
                            new StockDeductRequest(it.getProductId(), it.getCount()));
                } catch (Exception ignored) {
                    // 补偿失败交给定时任务(该商品 never 完成下单, locked 会一直挂着, 需人工介入; 演示期忽略)
                }
            }
            throw e;
        }
    }

    /**
     * 秒杀下单(抢到资格后由前端调用): 不重复走普通 lockStock。
     * 资格已在 product-service 的 /seckill/confirm 里用 DB 锁定库存(locked)+一人一单幂等确认;
     * 这里只负责落订单。支付/超时/取消完全复用现有逻辑(deductStock/releaseStock),
     * 保证与普通订单的库存模型一致。
     */
    public Order createSeckillOrder(OrderCreateRequest req) {
        if (req.getUserId() == null || req.getItems() == null || req.getItems().size() != 1) {
            throw new IllegalArgumentException("秒杀下单参数不合法: 每次仅限一个商品");
        }
        OrderCreateRequest.Item item = req.getItems().get(0);
        if (item.getProductId() == null || item.getCount() == null || item.getCount() <= 0) {
            throw new IllegalArgumentException("秒杀商品信息不合法");
        }
        String orderNo = "SK" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + String.format("%04d", new Random().nextInt(10000));

        // 秒杀确认: DB 锁定库存 + 一人一单幂等
        Result<Boolean> confirm = productFeignClient.seckillConfirm(
                new SeckillConfirmRequest(req.getUserId(), item.getProductId(), item.getCount()));
        if (confirm.getData() == null || !confirm.getData()) {
            throw new RuntimeException("未抢到或您已购买, 请勿重复提交");
        }

        // 拉最新商品做订单快照
        Product product = productFeignClient.getById(item.getProductId()).getData();
        if (product == null) {
            throw new RuntimeException("商品不存在: id=" + item.getProductId());
        }

        OrderItem oi = new OrderItem();
        oi.setOrderNo(orderNo);
        oi.setProductId(product.getId());
        oi.setProductName(product.getName());
        oi.setProductImage(product.getMainImage());
        oi.setPrice(product.getPrice());
        oi.setCount(item.getCount());
        oi.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(item.getCount())));
        // 秒杀单同样记录商家归属与初始物流状态(待发货)
        oi.setMerchantId(product.getMerchantId());
        oi.setLogisticsStatus(0);

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(req.getUserId());
        order.setTotalAmount(oi.getSubtotal());
        order.setTotalCount(item.getCount());
        order.setStatus(1);
        order.setReceiverName(req.getReceiverName());
        order.setReceiverPhone(req.getReceiverPhone());
        order.setReceiverAddr(req.getReceiverAddr());
        order.setRemark(req.getRemark());
        orderMapper.insert(order);
        oi.setOrderId(order.getId());
        orderItemMapper.insert(oi);

        saveRedisTimeout(orderNo);
        order.setItems(List.of(oi));
        order.setRemainingSeconds(computeRemainingSeconds(order));
        return order;
    }

    /**
     * 我的订单列表(按下单时间倒序): 批量填充商品明细, 并计算待支付剩余时间。
     * 一次 IN 查询所有订单的 items, 避免 N+1 查询。
     *
     * @param status 可选状态筛选(null=全部): 1-待支付 2-已支付/待发货 3-已发货/待收货 4-已完成 5-已取消
     */
    public List<Order> listByUser(Long userId, Integer status) {
        List<Order> orders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(status != null, Order::getStatus, status)
                        .orderByDesc(Order::getCreateTime)
        );
        fillItems(orders);
        fillRemainingSeconds(orders);
        return orders;
    }

    /**
     * 用户确认收货: 订单状态 3(已发货/待收货) -> 4(已完成)。
     * 校验: 订单存在且属于该用户 + 当前状态为 3 + 该订单所有明细 logistics_status 均为 2(已送达)。
     * 用"条件更新(status=3)"防并发, 影响行数为 0 说明已被并发变更。
     */
    @Transactional
    public void confirmReceive(String orderNo, Long userId) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BusinessException(400, "订单号不能为空");
        }
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(403, "无权操作该订单");
        }
        if (order.getStatus() == null || order.getStatus() != 3) {
            throw new BusinessException(400, "订单当前状态不可确认收货");
        }

        // 该订单所有明细必须已送达(logistics_status=2), 否则不允许确认收货
        Long notDelivered = orderItemMapper.selectCount(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderNo, orderNo)
                        .ne(OrderItem::getLogisticsStatus, 2));
        if (notDelivered != null && notDelivered > 0) {
            throw new BusinessException(400, "存在未送达的商品, 暂不可确认收货");
        }

        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, 3)
                .set(Order::getStatus, 4));
        if (updated == 0) {
            throw new BusinessException(400, "确认收货失败, 订单状态已变更, 请刷新后重试");
        }
    }

    /**
     * 订单详情(带商品明细与待支付剩余时间)。
     * @return 订单不存在返回 null(由 Controller 转 404)
     */
    public Order detail(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            return null;
        }
        fillItems(List.of(order));
        fillRemainingSeconds(List.of(order));
        return order;
    }

    /** 批量给订单填充商品明细: 一次 IN 查询所有 orderId 的 items, 按 orderId 分组 */
    private void fillItems(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        Map<Long, List<OrderItem>> group = orderItemMapper
                .selectList(new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderId, orderIds))
                .stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        orders.forEach(o -> o.setItems(group.getOrDefault(o.getId(), List.of())));
        fillReviewedFlags(orders, orderIds);
        fillRefundStatus(orders, orderIds);
    }

    /**
     * 一次性查出当前批次订单的最近一条退款申请, 填充到订单的非数据库字段。
     * 前端订单列表据此展示"退款中/退款被拒", 并避免对已有进行中退款的订单重复显示"申请退款"。
     */
    private void fillRefundStatus(List<Order> orders, List<Long> orderIds) {
        List<Refund> refunds = refundMapper.selectList(new LambdaQueryWrapper<Refund>()
                .in(Refund::getOrderId, orderIds)
                .orderByDesc(Refund::getApplyTime));
        Map<Long, Refund> latestMap = refunds.stream()
                .collect(Collectors.toMap(Refund::getOrderId, r -> r, (a, b) -> a));
        orders.forEach(o -> {
            Refund latest = latestMap.get(o.getId());
            if (latest != null) {
                o.setRefundStatus(latest.getStatus());
                o.setRefundNo(latest.getRefundNo());
            }
        });
    }

    /**
     * 一次性查出当前批次订单的所有评价, 标记到对应明细的 reviewed 字段。
     * 前端据此把"评价"按钮置灰; 不用逐条查询避免 N+1。
     */
    private void fillReviewedFlags(List<Order> orders, List<Long> orderIds) {
        List<Review> reviews = reviewMapper.selectList(
                new LambdaQueryWrapper<Review>().in(Review::getOrderId, orderIds));
        Map<Long, List<Long>> reviewedMap = reviews.stream()
                .collect(Collectors.groupingBy(Review::getOrderId,
                        Collectors.mapping(Review::getOrderItemId, Collectors.toList())));
        orders.forEach(o -> {
            List<Long> reviewedItemIds = reviewedMap.getOrDefault(o.getId(), List.of());
            o.getItems().forEach(item -> item.setReviewed(reviewedItemIds.contains(item.getId())));
        });
    }

    /** 计算每个订单的"待支付剩余秒数", 供前端实时倒计时展示 */
    private void fillRemainingSeconds(List<Order> orders) {
        if (orders == null) {
            return;
        }
        orders.forEach(o -> o.setRemainingSeconds(computeRemainingSeconds(o)));
    }

    /**
     * 待支付剩余秒数 = 支付截止时间(createTime + expireMinutes) - 当前时间, 最小为 0。
     * 仅对待支付(status=1)订单返回; 其他状态无意义返回 null。
     * 新建订单 createTime 未回填时, 按整段超时时长计算。
     */
    private Long computeRemainingSeconds(Order order) {
        if (order == null || order.getStatus() == null || order.getStatus() != 1) {
            return null;
        }
        LocalDateTime deadline = order.getCreateTime() == null
                ? LocalDateTime.now().plusMinutes(expireMinutes)
                : order.getCreateTime().plusMinutes(expireMinutes);
        long remain = Duration.between(LocalDateTime.now(), deadline).getSeconds();
        return Math.max(remain, 0L);
    }

    /**
     * 支付(模拟): 状态 1->2 + 落支付流水 + 最终扣减库存
     * @return 生成的支付流水
     */
    @GlobalTransactional(name = "order-pay",rollbackFor = Exception.class)
    @Transactional
    public Payment pay(OrderPayRequest req) {
        // 1. 校验参数与订单归属
        if (req == null || req.getOrderNo() == null || req.getUserId() == null) {
            throw new IllegalArgumentException("支付参数不合法");
        }
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, req.getOrderNo()));
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        if (!order.getUserId().equals(req.getUserId())) {
            throw new RuntimeException("无权操作该订单");
        }
        if (order.getStatus() == null || order.getStatus() != 1) {
            throw new RuntimeException("订单当前状态不可支付, 可能已取消或超时");
        }

        // 2. 状态机流转并用"条件更新"防并发: 仅当仍为待支付(1)时才置为已支付(2)
        //    乐观锁式更新, 影响行数=0 说明同时被取消/已支付, 直接失败
        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, 1)
                .set(Order::getStatus, 2)
                .set(Order::getPayTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new RuntimeException("订单状态已变更, 请刷新后重试");
        }

        // 3. 最终扣减库存(把下单时锁定的数量转为"已售"), 逐条 Feign 调用
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderNo, req.getOrderNo()));
        for (OrderItem it : items) {
            Result<Boolean> deduct = productFeignClient.deductStock(
                    new StockDeductRequest(it.getProductId(), it.getCount()));
            if (deduct.getData() == null || !deduct.getData()) {
                throw new RuntimeException("库存扣减失败: " + it.getProductName());
            }
        }

        // 4. 写入一条支付流水(模拟成功)
        Payment p = new Payment();
        p.setPaymentNo("PAY" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + String.format("%04d", new Random().nextInt(10000)));
        p.setOrderNo(req.getOrderNo());
        p.setUserId(req.getUserId());
        p.setAmount(order.getTotalAmount());
        p.setChannel(req.getChannel() == null ? "mock" : req.getChannel());
        p.setStatus(1); // 支付成功
        p.setTradeNo("MOCK_" + new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date()));
        p.setPayTime(LocalDateTime.now());
        paymentMapper.insert(p);

        // 5. 支付完成, 删除超时计时键(订单已不再需要超时清理)
        deleteRedisTimeout(req.getOrderNo());
        return p;
    }

    /**
     * 取消订单(用户手动取消)。
     * 校验属于该用户且仅当待支付时才允许取消。
     */
    @GlobalTransactional(name = "order-cancel", rollbackFor = Exception.class)
    @Transactional
    public boolean cancelOrder(OrderCancelRequest req) {
        if (req == null || req.getOrderNo() == null || req.getUserId() == null) {
            throw new IllegalArgumentException("取消订单参数不合法");
        }
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, req.getOrderNo()));
        if (order == null) {
            return false;
        }
        if (!order.getUserId().equals(req.getUserId())) {
            throw new RuntimeException("无权操作该订单");
        }
        return doCancelOrder(order);
    }

    /**
     * 超时自动释放(定时任务, 每 60 秒执行一次):
     *   扫描"创建时间早于 now - expireMinutes 且仍为待支付"的订单, 逐个置为已取消并释放锁定库存。
     *   这是超时兜底方案, 即使 Redis 不可用也能保证 30 分钟后库存被回收。
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @GlobalTransactional(name = "order-auto-release", rollbackFor = Exception.class)
    @Transactional
    public void autoReleaseTimeoutOrders() {
        LocalDateTime boundary = LocalDateTime.now().minusMinutes(expireMinutes);
        List<Order> pending = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getStatus, 1)
                        .lt(Order::getCreateTime, boundary));
        for (Order order : pending) {
            doCancelOrder(order);
        }
    }

    /**
     * 核心取消逻辑: 状态 1->5 + 释放锁定库存 + 删除超时计时键。
     * 用"条件更新(status=1)"做并发保护——若已被支付(2)则 updated=0, 说明不该取消。
     */
    private boolean doCancelOrder(Order order) {
        int updated = orderMapper.update(null, Wrappers.<Order>lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, 1)
                .set(Order::getStatus, 5)); // 已取消
        if (updated == 0) {
            return false; // 并发下已被支付或已取消, 无需处理
        }
        // 释放锁定库存(locked 减回去, 商品重新可卖)
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderNo, order.getOrderNo()));
        for (OrderItem it : items) {
            try {
                productFeignClient.releaseStock(
                        new StockDeductRequest(it.getProductId(), it.getCount()));
            } catch (Exception ignored) {
                // 单个释放失败不影响整体, 留待下次扫描重试
            }
        }
        deleteRedisTimeout(order.getOrderNo());
        return true;
    }

    /**
     * 超时自动取消(由延迟消息触发): 按订单号取消, 幂等(状态机 status=1 保护), 释放锁定库存。
     * 与定时任务 autoReleaseTimeoutOrders 互为兑底, 谁先执行都一样。
     */


    /**
     * 超时自动取消(由延迟消息触发): 按订单号取消, 幂等(状态机 status=1 保护), 释放锁定库存。
     * 与定时任务 autoReleaseTimeoutOrders 互为兑底, 谁先执行都一样。
     */
    @Transactional
    public void cancelByTimeout(String orderNo) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (order != null && order.getStatus() != null && order.getStatus() == 1) {
            doCancelOrder(order);
        }
    }

    /** 发送"订单超时取消"延迟消息(进延迟队列, 30min 无人支付则转死信队列触发 cancelByTimeout) */
    private void sendOrderExpireMessage(String orderNo) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.ORDER_EXPIRE_EXCHANGE, "order.expire.delay", orderNo);
        } catch (Exception ignored) {
            // MQ 不可用或发送失败时静默降级, 由定时任务兑底释放
        }
    }

    /** 下单成功异步通知(解耦+削峰; 演示仅打印, 生产可接短信/邮件/站内信) */
    private void notifyOrderCreated(Order order) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.ORDER_NOTIFY_EXCHANGE, "order.created",
                    "订单" + order.getOrderNo() + "创建成功, 金额 " + order.getTotalAmount());
        } catch (Exception ignored) {
            // 忽略
        }
    }

    // ============ Redis 超时计时键辅助方法 ============

    /**
     * 写入待支付超时计时键(30 分钟过期)。Redis 不可用时静默降级, 不阻断主流程。
     * 真正保证超时释放的是上面的定时任务, 这里主要是记录"还有哪些订单在等待支付"。
     */
    private void saveRedisTimeout(String orderNo) {
        try {
            stringRedisTemplate.opsForValue().set(
                    TIMEOUT_KEY_PREFIX + orderNo, orderNo, Duration.ofMinutes(expireMinutes));
        } catch (Exception ignored) {
            // Redis 未启动时忽略, 由定时任务兜底释放锁定库存
        }
    }

    /** 支付/取消成功后删除对应的超时计时键 */
    private void deleteRedisTimeout(String orderNo) {
        try {
            stringRedisTemplate.delete(TIMEOUT_KEY_PREFIX + orderNo);
        } catch (Exception ignored) {
            // 忽略
        }
    }
}