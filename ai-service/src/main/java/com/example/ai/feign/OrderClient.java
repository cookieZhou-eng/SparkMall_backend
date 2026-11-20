package com.example.ai.feign;

import com.example.common.entity.Order;
import com.example.common.entity.Refund;
import com.example.common.result.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * order-service Feign 客户端(订单/退款/物流类工具的后端实现)。
 *
 * 说明:
 *  - 物流 VO 与退款草稿 VO 是 order-service 私有 DTO, ai-service 不依赖 order-service 模块,
 *    故这两个接口返回 Result&lt;JsonNode&gt;, 由 ToolExecutor 直接透传给模型(字段读取只取关键字段),
 *    避免跨模块复制 DTO 后字段漂移;
 *  - /order/my 的 userId 是 @RequestParam(接口既有签名), 其余用户身份由 FeignUserHeaderConfig
 *    透传的 X-User-Id 请求头提供。
 */
@FeignClient("order-service")
public interface OrderClient {

    /** 我的订单列表(带明细; 注意该接口 userId 走请求参数, 为存量签名) */
    @GetMapping("/order/my")
    Result<List<Order>> myOrders(@RequestParam("userId") Long userId,
                                 @RequestParam("status") Integer status);

    /** 订单详情(带明细) */
    @GetMapping("/order/{id}")
    Result<Order> detail(@PathVariable("id") Long id);

    /** 订单明细物流轨迹(用户侧, X-User-Id 鉴权归属, 返回物流 VO 结构) */
    @GetMapping("/order/logistics/item/{itemId}")
    Result<JsonNode> itemLogistics(@PathVariable("itemId") Long itemId);

    /** 我的退款申请分页 */
    @GetMapping("/refund/my")
    Result<Result.PageData<Refund>> myRefunds(@RequestParam("page") Integer page,
                                              @RequestParam("size") Integer size);

    /** 某订单的退款申请记录(本人) */
    @GetMapping("/refund/order/{orderId}")
    Result<List<Refund>> refundsByOrder(@PathVariable("orderId") Long orderId);

    /** 退款申请草稿建议(仅生成建议不提交, 返回草稿 VO 结构) */
    @GetMapping("/refund/draft/{orderId}")
    Result<JsonNode> refundDraft(@PathVariable("orderId") Long orderId);
}
