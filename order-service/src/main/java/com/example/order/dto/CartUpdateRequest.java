package com.example.order.dto;

import lombok.Data;

/**
 * 更新购物车行请求: count / checked 均可选, 只更新传入的字段
 */
@Data
public class CartUpdateRequest {
    private Integer count;
    /** 是否勾选: 1-勾选 0-未勾选 */
    private Integer checked;
}
