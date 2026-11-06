package com.example.order.controller;

import com.example.common.result.Result;
import com.example.order.dto.CartAddRequest;
import com.example.order.dto.CartItemVO;
import com.example.order.dto.CartUpdateRequest;
import com.example.order.service.CartService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 购物车接口(网关默认鉴权, 所有接口都要求登录)
 */
@RestController
@RequestMapping("/cart")
public class CartController {

    @Resource
    private CartService cartService;

    /**
     * 购物车列表(含商品实时价格/库存/有效性)
     */
    @GetMapping("/list")
    public Result<List<CartItemVO>> list(
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return Result.success(cartService.list(userId));
    }

    /**
     * 加入购物车(已存在则数量累加)
     */
    @PostMapping("/add")
    public Result<CartItemVO> add(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                  @RequestBody CartAddRequest req) {
        return Result.success(cartService.add(userId, req));
    }

    /**
     * 全选 / 取消全选
     */
    @PutMapping("/check-all")
    public Result<Void> checkAll(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                 @RequestBody CartUpdateRequest req) {
        cartService.checkAll(userId, req == null ? null : req.getChecked());
        return Result.success();
    }

    /**
     * 更新数量 / 勾选状态
     */
    @PutMapping("/{id}")
    public Result<CartItemVO> update(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                     @PathVariable Long id,
                                     @RequestBody CartUpdateRequest req) {
        return Result.success(cartService.update(userId, id, req));
    }

    /**
     * 删除单行
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                               @PathVariable Long id) {
        cartService.delete(userId, id);
        return Result.success();
    }

    /**
     * 清空购物车; 传 productIds 时只删这些商品(下单成功后清理已购商品)
     */
    @DeleteMapping
    public Result<Void> clear(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                              @RequestParam(required = false) List<Long> productIds) {
        cartService.clear(userId, productIds);
        return Result.success();
    }

    /**
     * 购物车商品总件数(角标)
     */
    @GetMapping("/count")
    public Result<Long> count(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return Result.success(cartService.count(userId));
    }
}
