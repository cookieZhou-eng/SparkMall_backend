package com.example.product.controller;

import com.example.common.dto.StockDeductRequest;
import com.example.common.entity.Category;
import com.example.common.entity.Product;
import com.example.common.result.Result;
import com.example.product.service.ProductService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品控制器(薄控制器)
 *
 * 分层说明:
 *  - 只做"参数接收 + 基础校验 + Result 封装", 业务逻辑(含 Redis 缓存)在 ProductService。
 *  - order-service 通过 Feign 调用这里的库存接口, 返回格式保持 Result<T> 不变。
 */
@RestController
@RequestMapping("/product")
public class ProductController {

    @Resource
    private ProductService productService;

    /**
     * 商品列表(支持按分类筛选 + 关键词搜索 + 分页 + 排序)
     * @param sort 可选排序: sales=销量倒序 / priceAsc=价格升序 / priceDesc=价格降序 / 空=创建时间倒序
     */
    @GetMapping("/list")
    public Result<Result.PageData<Product>> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "8") Integer size,
            @RequestParam(required = false) String sort) {
        return Result.success(productService.list(categoryId, keyword, page, size, sort));
    }

    /**
     * 按商家查上架商品(公开, 商家主页使用): status=1 且未删除, 按创建时间倒序
     * 注意: 与下方 /{id} 不冲突, /merchant/5 为两段路径不会命中单段变量 /{id}
     */
    @GetMapping("/merchant/{merchantId}")
    public Result<List<Product>> listByMerchant(@PathVariable Long merchantId) {
        return Result.success(productService.listByMerchant(merchantId));
    }

    /**
     * 商品详情(Cache-Aside 在 Service 中实现)
     */
    @GetMapping("/{id}")
    public Result<Product> getById(@PathVariable Long id) {
        Product product = productService.getById(id);
        if (product == null) {
            return Result.fail(404, "商品不存在");
        }
        return Result.success(product);
    }

    /**
     * 分类树(读多写少, 走 Redis 缓存)
     */
    @GetMapping("/categories")
    public Result<List<Category>> categories() {
        return Result.success(productService.categories());
    }

    /**
     * 锁定库存(下单未支付时由 order-service 通过 Feign 调用)
     */
    @PostMapping("/lockStock")
    public Result<Boolean> lockStock(@RequestBody StockDeductRequest req) {
        if (req == null || req.getProductId() == null || req.getCount() == null || req.getCount() <= 0) {
            return Result.fail(400, "锁定库存参数不合法");
        }
        return Result.success(productService.lockStock(req));
    }

    /**
     * 释放锁定库存(订单超时未支付 / 用户取消订单时由 order-service 调用)
     */
    @PostMapping("/releaseStock")
    public Result<Boolean> releaseStock(@RequestBody StockDeductRequest req) {
        if (req == null || req.getProductId() == null || req.getCount() == null || req.getCount() <= 0) {
            return Result.fail(400, "释放库存参数不合法");
        }
        return Result.success(productService.releaseStock(req));
    }

    /**
     * 支付确认时的"最终扣减"(用户支付成功后由 order-service 调用)
     */
    @PostMapping("/deductStock")
    public Result<Boolean> deductStock(@RequestBody StockDeductRequest req) {
        if (req == null || req.getProductId() == null || req.getCount() == null || req.getCount() <= 0) {
            return Result.fail(400, "扣库存参数不合法");
        }
        return Result.success(productService.deductStock(req));
    }
}
