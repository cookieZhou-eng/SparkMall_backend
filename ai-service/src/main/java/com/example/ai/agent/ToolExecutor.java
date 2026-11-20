package com.example.ai.agent;

import com.example.ai.feign.OrderClient;
import com.example.ai.feign.ProductClient;
import com.example.common.entity.Category;
import com.example.common.entity.Order;
import com.example.common.entity.Product;
import com.example.common.entity.Refund;
import com.example.common.result.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具执行器: 把模型的 Function Calling 落到现有微服务接口上。
 *
 * 设计要点:
 *  - userId 由后端注入(来自网关透传的 X-User-Id), 模型无法伪造用户身份;
 *  - 返回值统一是 JSON 字符串, 直接作为 role=tool 消息回传给模型;
 *  - 下游接口失败不抛异常打断对话, 而是返回 {"error": ...} 让模型自行向用户解释;
 *  - 输出做字段裁剪(只给模型需要的字段), 控制上下文长度。
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    /** 每页最大返回条数(限制工具输出体积) */
    private static final int MAX_PAGE_SIZE = 10;
    /** 商品搜索一次拉取的条数(list 接口无价格区间参数, 拉一批后在内存里过滤) */
    private static final int FETCH_SIZE = 20;

    /** 订单状态名映射 */
    private static final Map<Integer, String> ORDER_STATUS = Map.of(
            1, "待支付", 2, "已支付", 3, "已发货", 4, "已完成", 5, "已取消", 6, "已退款");
    /** 订单明细物流状态名映射 */
    private static final Map<Integer, String> ITEM_LOGISTICS_STATUS = Map.of(
            0, "待发货", 1, "已发货", 2, "已送达");
    /** 退款单状态名映射 */
    private static final Map<Integer, String> REFUND_STATUS = Map.of(
            1, "待审核", 2, "已通过", 3, "已拒绝", 4, "退款中", 5, "已退款");
    /** 退款类型名映射 */
    private static final Map<Integer, String> REFUND_TYPE = Map.of(
            1, "仅退款", 2, "退货退款");

    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private ProductClient productClient;
    @Resource
    private OrderClient orderClient;

    /**
     * 执行工具调用。
     * @param userId   当前登录用户(网关透传, 由 AgentService 注入)
     * @param name     工具名
     * @param argsJson 模型给的 JSON 参数串
     * @return JSON 字符串结果(含 error 时表示执行失败)
     */
    public String execute(Long userId, String name, String argsJson) {
        try {
            JsonNode args = (argsJson == null || argsJson.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(argsJson);
            Object result = switch (name) {
                case "searchProducts" -> searchProducts(args);
                case "getProductDetail" -> getProductDetail(args);
                case "getCategories" -> getCategories();
                case "queryMyOrders" -> queryMyOrders(userId, args);
                case "getOrderDetail" -> getOrderDetail(userId, args);
                case "getLogisticsTrace" -> getLogisticsTrace(args);
                case "queryMyRefunds" -> queryMyRefunds(userId, args);
                case "getRefundByOrder" -> getRefundByOrder(args);
                case "applyRefundDraft" -> applyRefundDraft(userId, args);
                default -> Map.of("error", "未知工具: " + name);
            };
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("工具执行失败: name={} args={}", name, argsJson, e);
            return toJson(Map.of("error", "工具执行失败, 请稍后重试"));
        }
    }

    // ==================== 商品类工具 ====================

    /**
     * searchProducts: /product/list 不支持价格区间参数,
     * 先按关键词/排序拉一批, 再在内存里做 min/maxPrice 过滤(演示量级足够)。
     */
    private Object searchProducts(JsonNode args) {
        String sortBy = text(args, "sortBy");
        String sort = switch (sortBy == null ? "default" : sortBy) {
            case "sales" -> "sales";
            case "price_asc" -> "priceAsc";
            case "price_desc" -> "priceDesc";
            default -> null; // 默认排序(创建时间倒序)
        };
        Result<Result.PageData<Product>> resp = productClient.list(
                longValue(args, "categoryId"), text(args, "keyword"), 1, FETCH_SIZE, sort);
        if (!ok(resp)) {
            return Map.of("error", message(resp, "商品查询失败"));
        }
        List<Product> all = resp.getData() == null || resp.getData().getRecords() == null
                ? List.of() : resp.getData().getRecords();
        BigDecimal minPrice = decimal(args, "minPrice");
        BigDecimal maxPrice = decimal(args, "maxPrice");
        List<Product> filtered = all.stream()
                .filter(p -> p.getPrice() != null)
                .filter(p -> minPrice == null || p.getPrice().compareTo(minPrice) >= 0)
                .filter(p -> maxPrice == null || p.getPrice().compareTo(maxPrice) <= 0)
                .toList();
        int pageNum = intOrDefault(args, "pageNum", 1);
        int pageSize = pageSize(args);
        List<Map<String, Object>> products = filtered.stream()
                .skip((long) Math.max(0, pageNum - 1) * pageSize)
                .limit(pageSize)
                .map(this::productBrief)
                .collect(Collectors.toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", filtered.size());
        out.put("products", products);
        return out;
    }

    /** getProductDetail */
    private Object getProductDetail(JsonNode args) {
        Long productId = longValue(args, "productId");
        if (productId == null) {
            return Map.of("error", "缺少 productId 参数");
        }
        Result<Product> resp = productClient.getById(productId);
        if (!ok(resp) || resp.getData() == null) {
            return Map.of("error", message(resp, "商品不存在或已下架"));
        }
        Product p = resp.getData();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.getId());
        out.put("name", p.getName());
        out.put("subtitle", p.getSubtitle());
        out.put("price", p.getPrice());
        out.put("originalPrice", p.getOriginalPrice());
        out.put("sales", p.getSales());
        out.put("stock", p.getStock());
        return out;
    }

    /** getCategories: 树拍平成 id/parentId/name 列表, 省上下文 */
    private Object getCategories() {
        Result<List<Category>> resp = productClient.categories();
        if (!ok(resp) || resp.getData() == null) {
            return Map.of("error", message(resp, "分类查询失败"));
        }
        List<Map<String, Object>> flat = new ArrayList<>();
        for (Category c : resp.getData()) {
            flat.add(categoryBrief(c));
            if (c.getChildren() != null) {
                c.getChildren().forEach(child -> flat.add(categoryBrief(child)));
            }
        }
        return Map.of("categories", flat);
    }

    // ==================== 订单类工具 ====================

    /** queryMyOrders */
    private Object queryMyOrders(Long userId, JsonNode args) {
        Result<List<Order>> resp = orderClient.myOrders(userId, intValue(args, "status"));
        if (!ok(resp) || resp.getData() == null) {
            return Map.of("error", message(resp, "订单查询失败"));
        }
        int pageNum = intOrDefault(args, "pageNum", 1);
        int pageSize = pageSize(args);
        List<Order> orders = resp.getData();
        List<Map<String, Object>> items = orders.stream()
                .skip((long) Math.max(0, pageNum - 1) * pageSize)
                .limit(pageSize)
                .map(this::orderBrief)
                .collect(Collectors.toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", orders.size());
        out.put("orders", items);
        return out;
    }

    /** getOrderDetail: 仅限本人订单(归属校验在后端兜底, 模型拿不到他人数据) */
    private Object getOrderDetail(Long userId, JsonNode args) {
        Long orderId = longValue(args, "orderId");
        if (orderId == null) {
            return Map.of("error", "缺少 orderId 参数");
        }
        Result<Order> resp = orderClient.detail(orderId);
        if (!ok(resp) || resp.getData() == null) {
            return Map.of("error", message(resp, "订单不存在"));
        }
        Order order = resp.getData();
        if (!userId.equals(order.getUserId())) {
            return Map.of("error", "无权查看该订单");
        }
        Map<String, Object> out = orderBrief(order);
        out.put("receiverName", order.getReceiverName());
        out.put("receiverAddr", order.getReceiverAddr());
        out.put("receiverPhone", maskPhone(order.getReceiverPhone())); // 脱敏展示
        return out;
    }

    /** getLogisticsTrace: 物流 VO 结构直接透传(见 OrderClient 注释) */
    private Object getLogisticsTrace(JsonNode args) {
        Long itemId = longValue(args, "orderItemId");
        if (itemId == null) {
            return Map.of("error", "缺少 orderItemId 参数");
        }
        Result<JsonNode> resp = orderClient.itemLogistics(itemId);
        if (!ok(resp) || resp.getData() == null) {
            return Map.of("error", message(resp, "该商品尚未发货, 暂无物流信息"));
        }
        return resp.getData();
    }

    // ==================== 退款类工具 ====================

    /** queryMyRefunds */
    private Object queryMyRefunds(Long userId, JsonNode args) {
        int pageNum = intOrDefault(args, "pageNum", 1);
        Result<Result.PageData<Refund>> resp = orderClient.myRefunds(pageNum, pageSize(args));
        if (!ok(resp) || resp.getData() == null) {
            return Map.of("error", message(resp, "退款查询失败"));
        }
        List<Refund> records = resp.getData().getRecords() == null
                ? List.of() : resp.getData().getRecords();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", resp.getData().getTotal());
        out.put("refunds", records.stream().map(this::refundBrief).collect(Collectors.toList()));
        return out;
    }

    /** getRefundByOrder */
    private Object getRefundByOrder(JsonNode args) {
        Long orderId = longValue(args, "orderId");
        if (orderId == null) {
            return Map.of("error", "缺少 orderId 参数");
        }
        Result<List<Refund>> resp = orderClient.refundsByOrder(orderId);
        if (!ok(resp) || resp.getData() == null) {
            return Map.of("error", message(resp, "退款查询失败"));
        }
        return Map.of("refunds", resp.getData().stream()
                .map(this::refundBrief).collect(Collectors.toList()));
    }

    /** applyRefundDraft: 只生成草稿建议, 提交动作引导用户去订单页完成 */
    private Object applyRefundDraft(Long userId, JsonNode args) {
        Long orderId = longValue(args, "orderId");
        String reason = text(args, "reason");
        if (orderId == null) {
            return Map.of("error", "缺少 orderId 参数");
        }
        if (reason == null || reason.isBlank()) {
            return Map.of("error", "缺少 reason 参数");
        }
        Result<JsonNode> resp = orderClient.refundDraft(orderId);
        if (!ok(resp) || resp.getData() == null) {
            return Map.of("error", message(resp, "生成退款草稿失败"));
        }
        JsonNode draft = resp.getData();
        int suggestedType = draft.path("suggestedType").asInt(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", draft.path("orderId").asLong());
        out.put("orderNo", draft.path("orderNo").asText());
        out.put("amount", draft.path("amount").decimalValue());
        out.put("suggestedType", suggestedType);
        out.put("suggestedTypeName", REFUND_TYPE.getOrDefault(suggestedType, "未知"));
        out.put("userReason", reason);
        out.put("note", "草稿仅供确认, 请到订单页正式提交退款申请");
        return out;
    }

    // ==================== 输出组装与参数工具方法 ====================

    /** 商品列表项(只给模型需要的字段) */
    private Map<String, Object> productBrief(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("subtitle", p.getSubtitle());
        m.put("price", p.getPrice());
        m.put("originalPrice", p.getOriginalPrice());
        m.put("sales", p.getSales());
        return m;
    }

    /** 订单摘要(含明细商品与明细物流状态) */
    private Map<String, Object> orderBrief(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.getId());
        m.put("orderNo", o.getOrderNo());
        m.put("status", o.getStatus());
        m.put("statusName", ORDER_STATUS.getOrDefault(o.getStatus(), "未知"));
        m.put("totalAmount", o.getTotalAmount());
        m.put("totalCount", o.getTotalCount());
        m.put("createTime", o.getCreateTime());
        List<Map<String, Object>> items = new ArrayList<>();
        if (o.getItems() != null) {
            for (var item : o.getItems()) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("itemId", item.getId());
                im.put("productId", item.getProductId());
                im.put("productName", item.getProductName());
                im.put("price", item.getPrice());
                im.put("count", item.getCount());
                im.put("logisticsStatus", item.getLogisticsStatus());
                im.put("logisticsStatusName",
                        ITEM_LOGISTICS_STATUS.getOrDefault(item.getLogisticsStatus(), "未知"));
                items.add(im);
            }
        }
        m.put("items", items);
        return m;
    }

    /** 退款单摘要 */
    private Map<String, Object> refundBrief(Refund r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("refundNo", r.getRefundNo());
        m.put("orderId", r.getOrderId());
        m.put("orderNo", r.getOrderNo());
        m.put("type", r.getType());
        m.put("typeName", REFUND_TYPE.getOrDefault(r.getType(), "未知"));
        m.put("status", r.getStatus());
        m.put("statusName", REFUND_STATUS.getOrDefault(r.getStatus(), "未知"));
        m.put("amount", r.getAmount());
        m.put("reason", r.getReason());
        m.put("auditRemark", r.getAuditRemark());
        m.put("applyTime", r.getApplyTime());
        return m;
    }

    private Map<String, Object> categoryBrief(Category c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("parentId", c.getParentId());
        m.put("name", c.getName());
        return m;
    }

    /** Feign 返回 code 是否为 200 */
    private boolean ok(Result<?> resp) {
        return resp != null && resp.getCode() != null && resp.getCode() == 200;
    }

    /** 下游失败时返回其 message, 为空则用兜底文案 */
    private String message(Result<?> resp, String fallback) {
        return resp != null && resp.getMessage() != null && !resp.getMessage().isBlank()
                ? resp.getMessage() : fallback;
    }

    /** 手机号脱敏: 保留前 3 后 4 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 8) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String text(JsonNode args, String key) {
        JsonNode n = args.get(key);
        return n == null || n.isNull() ? null : n.asText();
    }

    private Long longValue(JsonNode args, String key) {
        JsonNode n = args.get(key);
        return n == null || n.isNull() || !n.canConvertToLong() ? null : n.asLong();
    }

    private Integer intValue(JsonNode args, String key) {
        JsonNode n = args.get(key);
        return n == null || n.isNull() ? null : n.asInt();
    }

    private int intOrDefault(JsonNode args, String key, int def) {
        Integer v = intValue(args, key);
        return v == null || v < 1 ? def : v;
    }

    /** pageSize 默认 5, 上限 MAX_PAGE_SIZE */
    private int pageSize(JsonNode args) {
        int size = intOrDefault(args, "pageSize", 5);
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private BigDecimal decimal(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(n.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"结果序列化失败\"}";
        }
    }
}
