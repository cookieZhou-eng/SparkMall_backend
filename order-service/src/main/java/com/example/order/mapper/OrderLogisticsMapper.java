package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.OrderLogistics;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单物流单 Mapper(模拟快递): 商家发货创建, 定时任务推进轨迹状态
 */
@Mapper
public interface OrderLogisticsMapper extends BaseMapper<OrderLogistics> {
}
