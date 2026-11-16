package com.example.user.feign;

import com.example.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * product-service 内部调用客户端(服务间通信, 不经网关)。
 *
 * 用途: 管理端禁用商家账号时, 级联下架该商家的全部在售商品,
 * 保证被禁用商家的商品立即从市场中撤下。
 */
@FeignClient(name = "product-service", contextId = "productInternalClient")
public interface ProductFeignClient {

    /**
     * 按商家强制下架其全部在售商品
     * @return 本次下架的商品数量
     */
    @PostMapping("/internal/product/offShelfByMerchant")
    Result<Integer> offShelfByMerchant(@RequestParam("merchantId") Long merchantId);
}
