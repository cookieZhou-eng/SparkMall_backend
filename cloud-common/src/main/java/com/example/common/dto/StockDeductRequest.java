package com.example.common.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 扣库存请求 DTO
 * 放在 cloud-common 供两个服务共用:
 *  - order-service 通过 Feign 传给它当请求体
 *  - product-service 接收它做库存扣减
 * 字段校验在后端接口处进行, 此处保持精简
 */
@Data
@NoArgsConstructor
public class StockDeductRequest {
    private Long productId;   // 商品ID
    private Integer count;    // 扣减数量(正整数)

    public StockDeductRequest(Long productId, Integer count) {
        this.productId = productId;
        this.count = count;
    }
}
