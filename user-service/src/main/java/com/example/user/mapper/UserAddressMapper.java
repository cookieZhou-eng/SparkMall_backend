package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.UserAddress;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收货地址 Mapper(逻辑删除由 @TableLogic 自动处理)
 */
@Mapper
public interface UserAddressMapper extends BaseMapper<UserAddress> {
}
