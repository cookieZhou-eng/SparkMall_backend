package com.example.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.constant.AuthKeys;
import com.example.common.entity.User;
import com.example.common.result.Result;
import com.example.user.dto.UserVO;
import com.example.user.exception.BusinessException;
import com.example.user.mapper.UserMapper;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 管理端用户管理业务。
 *
 * 分层说明:
 *  - Controller 只做参数接收与 Result 封装, 查询/校验/状态变更在本 Service。
 *  - 网关已校验 role=admin 并透传 X-User-Role, 这里再校验一次(双保险, 防止绕过网关直连)。
 */
@Service
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 100;

    @Resource
    private UserMapper userMapper;
    /** 禁用/启用账号时同步 Redis 状态键, 网关据此即时拦截存量 token */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 用户分页列表 —— 职责收敛: 用户管理只面向普通顾客账号。
     * 商家账号统一走 /admin/merchant 商家管理(含级联下架等商家专属操作);
     * 管理员账号不出现在列表中, 也不允许被禁用。
     */
    public Result.PageData<UserVO> list(String role, String keyword,
                                        Integer status, Integer page, Integer size) {
        checkAdmin(role);
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, MAX_PAGE_SIZE);

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(User::getRole, "customer"); // 只管理普通用户(顾客)
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            wrapper.and(w -> w.like(User::getUsername, kw)
                    .or().like(User::getNickname, kw)
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
     * 用户统计(数据看板用): 总数、顾客数、商家数、管理员数、禁用数
     */
    public Map<String, Long> count(String role) {
        checkAdmin(role);
        Map<String, Long> data = new LinkedHashMap<>();
        data.put("totalUsers", userMapper.selectCount(null));
        data.put("customers", userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getRole, "customer")));
        data.put("merchants", userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getRole, "merchant")));
        data.put("admins", userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getRole, "admin")));
        data.put("disabledUsers", userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getStatus, 0)));
        return data;
    }

    /**
     * 启用/禁用用户(仅普通顾客账号):
     *  - 商家账号请走商家管理(禁用商家需级联下架商品, 逻辑不同);
     *  - 管理员账号不允许禁用, 防止误操作导致后台无法登录;
     *  - 禁用时写 Redis 状态键为 "0", 网关立即拦截该账号的存量 token(无需等 7 天过期)。
     */
    public void updateStatus(String role, Long id, Integer status) {
        checkAdmin(role);
        if (id == null || status == null || (status != 0 && status != 1)) {
            throw new BusinessException(400, "用户ID或状态参数不合法");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (!"customer".equals(user.getRole())) {
            throw new BusinessException(400, "用户管理仅支持普通顾客账号, 商家请到「商家管理」操作");
        }
        if (status == 0 && user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(400, "该用户已被禁用");
        }
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, id)
                .set(User::getStatus, status));
        // 同步账号状态到 Redis: 禁用→"0"(存量 token 立即失效), 启用→"1"(恢复)
        stringRedisTemplate.opsForValue().set(
                AuthKeys.USER_STATUS_PREFIX + id, status == 1 ? "1" : "0", 7, TimeUnit.DAYS);
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
