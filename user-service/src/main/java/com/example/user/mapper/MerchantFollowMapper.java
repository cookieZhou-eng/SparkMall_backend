package com.example.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.MerchantFollow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商家收藏 Mapper
 * (user_id, merchant_id) 数据库唯一键兜底防重复, 无需额外逻辑删除
 */
@Mapper
public interface MerchantFollowMapper extends BaseMapper<MerchantFollow> {
}
