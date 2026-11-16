package com.example.product.controller;

import com.example.common.result.Result;
import com.example.product.service.InternalProductService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品服务内部接口(仅供服务间 Feign 调用, 不经网关)。
 *
 * 安全说明: 网关路由表没有 /internal/** 前缀, 且 GlobalAuthFilter 会拦截任何
 * 路径中含 internal 段的请求(防止 Nacos discovery locator 自动路由绕过),
 * 因此外部无法访问本控制器, 只有集群内服务可以调用。
 */
@RestController
@RequestMapping("/internal/product")
public class InternalProductController {

    private final InternalProductService internalProductService;

    public InternalProductController(InternalProductService internalProductService) {
        this.internalProductService = internalProductService;
    }

    /**
     * 商家被禁用时级联下架其全部在售商品(user-service 管理端禁用商家时调用)。
     * @return 强制下架的商品数量
     */
    @PostMapping("/offShelfByMerchant")
    public Result<Integer> offShelfByMerchant(@RequestParam("merchantId") Long merchantId) {
        if (merchantId == null) {
            return Result.fail(400, "merchantId 不能为空");
        }
        return Result.success(internalProductService.offShelfByMerchant(merchantId));
    }
}
