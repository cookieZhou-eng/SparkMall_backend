package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.Order;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单 Mapper
 * 继承 BaseMapper<Order> 后自动获得单表 CRUD, 无需写 SQL
 * 注意: t_order 上有 @TableLogic(isDeleted), MP 会自动追加 is_deleted=0 过滤
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
