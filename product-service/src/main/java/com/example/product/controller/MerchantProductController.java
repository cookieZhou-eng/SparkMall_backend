package com.example.product.controller;

import com.example.common.entity.Product;
import com.example.common.result.Result;
import com.example.product.dto.MerchantProductRequest;
import com.example.product.dto.MerchantProductStatusRequest;
import com.example.product.service.MerchantProductService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 商家商品控制器(薄控制器): 网关 /merchant/** 路由转发过来。
 *
 * 分层说明:
 *  - 只做参数接收与 Result 封装, 归属/角色/参数校验与业务逻辑在 MerchantProductService。
 *  - 网关已校验 JWT 且 role=merchant, 并透传 X-User-Id / X-User-Role;
 *    Service 内再做一次角色校验(双保险), 防止绕过网关直连。
 */
@RestController
@RequestMapping("/merchant")
public class MerchantProductController {

    @Resource
    private MerchantProductService merchantProductService;

    /**
     * 我的商品列表(含已下架), 按创建时间倒序
     * @param name 可选, 商品名称模糊搜索
     * @param categoryId 可选, 分类筛选(含所有子分类)
     */
    @GetMapping("/product/list")
    public Result<Result.PageData<Product>> list(
            @RequestHeader("X-User-Id") Long merchantId,
            @RequestHeader("X-User-Role") String role,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Long categoryId) {
        return Result.success(
                merchantProductService.listMyProducts(merchantId, role, page, size, name, categoryId));
    }

    /**
     * 新增商品: 插入 t_product + t_product_stock(事务), 默认上架
     */
    @PostMapping("/product")
    public Result<Long> create(
            @RequestHeader("X-User-Id") Long merchantId,
            @RequestHeader("X-User-Role") String role,
            @RequestBody MerchantProductRequest req) {
        return Result.success(merchantProductService.createProduct(merchantId, role, req));
    }

    /**
     * 编辑商品(stock 可选, 传入则修改库存): 校验归属, 更新后失效详情缓存
     */
    @PutMapping("/product")
    public Result<Void> update(
            @RequestHeader("X-User-Id") Long merchantId,
            @RequestHeader("X-User-Role") String role,
            @RequestBody MerchantProductRequest req) {
        merchantProductService.updateProduct(merchantId, role, req);
        return Result.success();
    }

    /**
     * 上架/下架切换: 校验归属, status=0 下架 / 1 上架
     */
    @PutMapping("/product/{id}/status")
    public Result<Void> updateStatus(
            @RequestHeader("X-User-Id") Long merchantId,
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id,
            @RequestBody MerchantProductStatusRequest req) {
        merchantProductService.updateStatus(merchantId, role, id, req);
        return Result.success();
    }

    /**
     * 删除商品(逻辑删除): 校验归属, 有锁定库存(未支付订单占用)时拒绝
     */
    @DeleteMapping("/product/{id}")
    public Result<Void> delete(
            @RequestHeader("X-User-Id") Long merchantId,
            @RequestHeader("X-User-Role") String role,
            @PathVariable Long id) {
        merchantProductService.deleteProduct(merchantId, role, id);
        return Result.success();
    }
}
