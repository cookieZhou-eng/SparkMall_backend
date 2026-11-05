package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.Payment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 支付流水 Mapper(写 t_payment 表)
 * 每次支付成功落一条流水: 渠道、金额、交易号、支付时间
 */
@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {
}