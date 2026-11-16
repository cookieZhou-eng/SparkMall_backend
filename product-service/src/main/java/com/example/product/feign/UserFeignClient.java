package com.example.product.feign;

import com.example.common.dto.MerchantBriefDTO;
import com.example.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * user-service 内部调用客户端(服务间通信, 不经网关)。
 *
 * 用途: 管理端商品列表/商品审核列表按 merchantId 批量回填商家店铺名等公开信息,
 * 避免逐个调用公开商家接口造成的 N+1 查询。
 */
@FeignClient(name = "user-service", contextId = "userInternalClient")
public interface UserFeignClient {

    /**
     * 按用户 id 批量查询商家简要信息(仅 role=merchant 的用户)
     * @param ids 逗号分隔的用户 id, 如 "1,2,3"
     */
    @GetMapping("/internal/user/merchant/batch")
    Result<List<MerchantBriefDTO>> merchantBatch(@RequestParam("ids") String ids);
}
