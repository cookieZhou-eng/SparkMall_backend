package com.example.user.controller;

import com.example.common.entity.UserAddress;
import com.example.common.result.Result;
import com.example.user.service.AddressService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 收货地址控制器(薄控制器)
 *
 * 接口一览:
 *   GET    /user/address?userId=     地址列表(默认地址排最前)
 *   POST   /user/address             新增地址(第一条自动设为默认)
 *   PUT    /user/address             修改地址
 *   PUT    /user/address/{id}/default?userId=  设为默认(先把其它置为非默认)
 *   DELETE /user/address/{id}?userId= 删除地址(逻辑删除; 删的是默认地址则自动补位)
 *
 * 分层说明:
 *  - 只做参数校验与 Result 封装, 业务规则(默认地址/事务)在 AddressService。
 */
@RestController
@RequestMapping("/user/address")
public class AddressController {

    @Resource
    private AddressService addressService;

    /** 地址列表: 默认地址排最前, 其余按创建时间倒序 */
    @GetMapping
    public Result<List<UserAddress>> list(@RequestParam Long userId) {
        return Result.success(addressService.list(userId));
    }

    /** 新增地址; 该用户还没有任何地址时, 自动把第一条设为默认 */
    @PostMapping
    public Result<UserAddress> add(@RequestBody UserAddress address) {
        if (address.getUserId() == null || isBlank(address.getReceiverName())
                || isBlank(address.getReceiverPhone()) || isBlank(address.getReceiverAddr())) {
            return Result.fail(400, "收货人/电话/地址不能为空");
        }
        return Result.success(addressService.add(address));
    }

    /** 修改地址(只允许改收货人/电话/地址, 防止前端把 userId/isDefault 等一并改掉) */
    @PutMapping
    public Result<Void> update(@RequestBody UserAddress address) {
        if (address.getId() == null) {
            return Result.fail(400, "缺少地址ID");
        }
        addressService.update(address);
        return Result.success(null);
    }

    /** 设为默认地址 */
    @PutMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id, @RequestParam Long userId) {
        addressService.setDefault(id, userId);
        return Result.success(null);
    }

    /** 删除地址(逻辑删除; 删的是默认地址则自动补位) */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, @RequestParam Long userId) {
        boolean deleted = addressService.delete(id, userId);
        if (!deleted) {
            return Result.fail(404, "地址不存在");
        }
        return Result.success(null);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
