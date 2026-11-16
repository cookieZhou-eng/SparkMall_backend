package com.example.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.common.dto.MerchantBriefDTO;
import com.example.common.entity.User;
import com.example.common.result.Result;
import com.example.user.mapper.UserMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户服务内部接口(仅供服务间 Feign 调用, 不经网关)。
 *
 * 安全说明: 网关路由表没有 /internal/** 前缀, 且 GlobalAuthFilter 会拦截任何
 * 路径中含 internal 段的请求(防止 Nacos discovery locator 自动路由绕过),
 * 因此外部无法访问本控制器, 只有集群内服务可以调用。
 */
@RestController
@RequestMapping("/internal/user")
public class InternalUserController {

    private final UserMapper userMapper;

    public InternalUserController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 按用户 id 批量查询商家简要信息(仅返回 role=merchant 的用户)。
     * 供 product-service 管理端商品/审核列表回填店铺名, 避免逐个调用公开商家接口的 N+1 查询。
     * @param ids 逗号分隔的用户 id, 如 "1,2,3"
     */
    @GetMapping("/merchant/batch")
    public Result<List<MerchantBriefDTO>> merchantBatch(@RequestParam("ids") String ids) {
        if (ids == null || ids.isBlank()) {
            return Result.success(List.of());
        }
        List<Long> idList = java.util.Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> s.matches("\\d+"))
                .map(Long::valueOf)
                .limit(100) // 上限保护: 一次最多回填 100 个商家
                .toList();
        if (idList.isEmpty()) {
            return Result.success(List.of());
        }
        List<MerchantBriefDTO> merchants = userMapper.selectList(
                        new LambdaQueryWrapper<User>()
                                .in(User::getId, idList)
                                .eq(User::getRole, "merchant"))
                .stream()
                .map(this::toBrief)
                .toList();
        return Result.success(merchants);
    }

    private MerchantBriefDTO toBrief(User user) {
        MerchantBriefDTO dto = new MerchantBriefDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setNickname(user.getNickname());
        dto.setShopName(user.getShopName());
        dto.setShopDesc(user.getShopDesc());
        dto.setAvatar(user.getAvatar());
        dto.setStatus(user.getStatus());
        return dto;
    }
}
