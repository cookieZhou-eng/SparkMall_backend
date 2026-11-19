package com.example.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.constant.AuthKeys;
import com.example.common.entity.User;
import com.example.common.result.Result;
import com.example.user.dto.UserVO;
import com.example.user.exception.BusinessException;
import com.example.user.feign.ProductFeignClient;
import com.example.user.mapper.UserMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 管理端商家管理业务。
 *
 * 与 AdminUserService 的区别: 这里只面向 role=merchant 的商家账号,
 * 提供店铺信息展示与"禁用商家"专属能力——禁用时通过 Feign 调用 product-service
 * 级联下架该商家全部在售商品, 保证被禁商家立即退出市场(重新启用后商品保持下架,
 * 由商家自行重新上架, 上架仍要求商品已过审)。
 */
@Slf4j
@Service
public class AdminMerchantService {

    private static final int MAX_PAGE_SIZE = 100;

    @Resource
    private UserMapper userMapper;
    /** 禁用/启用商家时同步 Redis 状态键, 网关据此即时拦截存量 token */
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ProductFeignClient productFeignClient;

    /**
     * 商家分页列表(固定 role=merchant), 支持用户名/店铺名/手机号关键词与状态筛选
     */
    public Result.PageData<UserVO> list(String role, String keyword, Integer status,
                                        Integer page, Integer size) {
        checkAdmin(role);
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, MAX_PAGE_SIZE);

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(User::getRole, "merchant");
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            wrapper.and(w -> w.like(User::getUsername, kw)
                    .or().like(User::getNickname, kw)
                    .or().like(User::getShopName, kw)
                    .or().like(User::getPhone, kw));
        }
        if (status != null) {
            wrapper.eq(User::getStatus, status);
        }
        wrapper.orderByDesc(User::getCreateTime);

        Page<User> pageResult = userMapper.selectPage(new Page<>(p, s), wrapper);
        List<UserVO> records = pageResult.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        return new Result.PageData<>(pageResult.getTotal(), records);
    }

    /**
     * 商家统计(数据看板用): 商家总数、启用数、禁用数
     */
    public Map<String, Long> count(String role) {
        checkAdmin(role);
        Map<String, Long> data = new LinkedHashMap<>();
        data.put("totalMerchants", userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getRole, "merchant")));
        data.put("enabledMerchants", userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getRole, "merchant").eq(User::getStatus, 1)));
        data.put("disabledMerchants", userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getRole, "merchant").eq(User::getStatus, 0)));
        return data;
    }

    /**
     * 商家详情(店铺信息)
     */
    public UserVO detail(String role, Long id) {
        checkAdmin(role);
        User user = requireMerchantUser(id);
        return toVO(user);
    }

    /**
     * 启用/禁用商家:
     *  - 仅允许操作 role=merchant 的账号(管理员/顾客走用户管理接口);
     *  - 禁用时级联下架该商家全部在售商品(product-service 内部接口), 并把下架数量透出给前端;
     *  - 级联下架失败时整体失败抛错, 提示管理员重试, 避免出现"账号已禁用但商品仍在售"的中间态。
     */
    public Map<String, Object> updateStatus(String role, Long id, Integer status) {
        checkAdmin(role);
        if (id == null || status == null || (status != 0 && status != 1)) {
            throw new BusinessException(400, "商家ID或状态参数不合法");
        }
        User user = requireMerchantUser(id);
        if (status == 0 && user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(400, "该商家已被禁用");
        }
        userMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<User>lambdaUpdate()
                        .eq(User::getId, id)
                        .set(User::getStatus, status));
        // 同步账号状态到 Redis: 禁用→"0"(存量 token 立即失效), 启用→"1"(恢复),
        // 与用户管理的禁用逻辑一致, 由网关统一拦截
        stringRedisTemplate.opsForValue().set(
                AuthKeys.USER_STATUS_PREFIX + id, status == 1 ? "1" : "0", 7, TimeUnit.DAYS);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("offShelfCount", 0);
        if (status == 0) {
            // 禁用商家 → 级联下架其在售商品
            try {
                Integer offShelfCount = productFeignClient.offShelfByMerchant(id).getData();
                data.put("offShelfCount", offShelfCount == null ? 0 : offShelfCount);
            } catch (Exception e) {
                log.error("禁用商家 {} 级联下架商品失败: {}", id, e.getMessage());
                throw new BusinessException(500, "禁用商家成功, 但级联下架商品失败, 请重试或联系运维");
            }
        }
        return data;
    }

    /** 校验目标用户存在且是商家, 否则抛 BusinessException */
    private User requireMerchantUser(Long id) {
        if (id == null) {
            throw new BusinessException(400, "商家 id 不能为空");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "商家不存在");
        }
        if (!"merchant".equals(user.getRole())) {
            throw new BusinessException(400, "该用户不是商家账号");
        }
        return user;
    }

    private void checkAdmin(String role) {
        if (!"admin".equals(role)) {
            throw new BusinessException(403, "无管理员权限");
        }
    }

    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setAvatar(user.getAvatar());
        vo.setGender(user.getGender());
        vo.setRole(user.getRole());
        vo.setShopName(user.getShopName());
        vo.setShopDesc(user.getShopDesc());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
