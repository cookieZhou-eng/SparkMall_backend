package com.example.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.common.entity.MerchantFollow;
import com.example.common.entity.User;
import com.example.user.dto.FollowedMerchantVO;
import com.example.user.exception.BusinessException;
import com.example.user.mapper.MerchantFollowMapper;
import com.example.user.mapper.UserMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 商家收藏服务: 收藏 / 取消收藏 / 收藏列表 / 是否已收藏。
 *
 * 设计说明:
 *  - 收藏关系表 t_merchant_follow, (user_id, merchant_id) 数据库唯一键兜底防重复;
 *  - 重复收藏 / 取消不存在的收藏均幂等处理(不报错), 前端按钮状态以"是否已收藏"接口为准;
 *  - 收藏列表只回填仍在职且未注销(未逻辑删除)的商家, 商家被删除后收藏行不展示。
 */
@Service
public class MerchantFollowService {

    @Resource
    private MerchantFollowMapper followMapper;
    @Resource
    private UserMapper userMapper;

    /**
     * 收藏商家(幂等): 已收藏直接返回成功
     * 校验: 商家必须存在且 role=merchant, 不允许收藏自己
     */
    public void follow(Long userId, Long merchantId) {
        if (userId.equals(merchantId)) {
            throw new BusinessException(400, "不能收藏自己");
        }
        User merchant = userMapper.selectById(merchantId);
        if (merchant == null || !"merchant".equals(merchant.getRole())) {
            throw new BusinessException(404, "商家不存在");
        }
        boolean exists = followMapper.exists(
                new LambdaQueryWrapper<MerchantFollow>()
                        .eq(MerchantFollow::getUserId, userId)
                        .eq(MerchantFollow::getMerchantId, merchantId)
        );
        if (exists) {
            return; // 幂等: 重复点击收藏按钮不报错
        }
        MerchantFollow follow = new MerchantFollow();
        follow.setUserId(userId);
        follow.setMerchantId(merchantId);
        followMapper.insert(follow);
    }

    /**
     * 取消收藏(幂等): 未收藏时直接返回成功
     */
    public void unfollow(Long userId, Long merchantId) {
        followMapper.delete(
                new LambdaQueryWrapper<MerchantFollow>()
                        .eq(MerchantFollow::getUserId, userId)
                        .eq(MerchantFollow::getMerchantId, merchantId)
        );
    }

    /**
     * 是否已收藏该商家(店铺页收藏按钮初始状态)
     */
    public boolean isFollowed(Long userId, Long merchantId) {
        return followMapper.exists(
                new LambdaQueryWrapper<MerchantFollow>()
                        .eq(MerchantFollow::getUserId, userId)
                        .eq(MerchantFollow::getMerchantId, merchantId)
        );
    }

    /**
     * 我的收藏商家列表: 按收藏时间倒序, 回填商家店铺信息
     */
    public List<FollowedMerchantVO> listFollowed(Long userId) {
        List<MerchantFollow> follows = followMapper.selectList(
                new LambdaQueryWrapper<MerchantFollow>()
                        .eq(MerchantFollow::getUserId, userId)
                        .orderByDesc(MerchantFollow::getCreateTime)
        );
        List<FollowedMerchantVO> result = new ArrayList<>();
        for (MerchantFollow follow : follows) {
            User merchant = userMapper.selectById(follow.getMerchantId());
            // 商家已被删除/注销时跳过(收藏行保留, 历史可追溯)
            if (merchant == null || !"merchant".equals(merchant.getRole())) {
                continue;
            }
            FollowedMerchantVO vo = new FollowedMerchantVO();
            vo.setMerchantId(merchant.getId());
            vo.setUsername(merchant.getUsername());
            vo.setShopName(merchant.getShopName());
            vo.setShopDesc(merchant.getShopDesc());
            vo.setAvatar(merchant.getAvatar());
            vo.setFollowTime(follow.getCreateTime());
            result.add(vo);
        }
        return result;
    }
}
