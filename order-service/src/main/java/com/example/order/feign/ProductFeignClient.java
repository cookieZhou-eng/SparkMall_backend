package com.example.order.feign;

import com.example.common.dto.SeckillConfirmRequest;
import com.example.common.dto.StockDeductRequest;
import com.example.common.entity.Product;
import com.example.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

// @FeignClient("product-service")：声明这是一个Feign客户端，并指定服务名称为"product-service"
// 定义一个Feign客户端接口，用于调用"product-service"服务的"/product/{id}"接口
// 即可实现多个服务之间的调用
@FeignClient("product-service")
public interface ProductFeignClient {
    // 定义一个GET请求，路径为"/product/{id}"，并将路径变量"id"绑定到方法参数"id"上
    @GetMapping("/product/{id}")
    Result<Product> getById(@PathVariable Long id);

    // 批量查询商品(购物车列表): 返回商品信息并带可用库存 stock
    @PostMapping("/product/batch")
    Result<List<Product>> batch(@RequestBody List<Long> ids);

    // 秒杀下单前确认: DB 锁定库存 + 一人一单幂等, 返回是否抢到资格
    @PostMapping("/seckill/confirm")
    Result<Boolean> seckillConfirm(@RequestBody SeckillConfirmRequest req);

    // 锁定库存: 下单流程中调用, 返回是否成功(可用库存不足返回 false)
    @PostMapping("/product/lockStock")
    Result<Boolean> lockStock(@RequestBody StockDeductRequest req);

    // 释放锁定库存: 订单超时未支付/取消时调用, 把锁定数量释放回可卖
    @PostMapping("/product/releaseStock")
    Result<Boolean> releaseStock(@RequestBody StockDeductRequest req);

    // 支付确认扣减: 用户支付成功后调用, 从"锁定"转为"已售"(stock 与 locked 同时减少)
    @PostMapping("/product/deductStock")
    Result<Boolean> deductStock(@RequestBody StockDeductRequest req);

    // 退款回补库存: 已真实卖出的商品退款后 stock 加回, 并回退销量
    @PostMapping("/product/restoreStock")
    Result<Boolean> restoreStock(@RequestBody StockDeductRequest req);
}