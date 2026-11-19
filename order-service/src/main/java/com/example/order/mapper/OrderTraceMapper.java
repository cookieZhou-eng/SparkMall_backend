package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.OrderTrace;
import org.apache.ibatis.annotations.Mapper;

/**
 * 物流轨迹 Mapper(模拟快递): 每次轨迹推进插入一行
 */
@Mapper
public interface OrderTraceMapper extends BaseMapper<OrderTrace> {
}
