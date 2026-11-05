package com.example.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.common.entity.UserAddress;
import com.example.user.mapper.UserAddressMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址服务: 地址增删改查业务逻辑。
 *
 * 分层说明:
 *  - 业务规则(首条自动默认 / 默认地址补位 / 白名单更新)与 @Transactional 收敛在本 Service,
 *    Controller 只做参数校验与 Result 封装。
 */
@Service
public class AddressService {

    @Resource
    private UserAddressMapper addressMapper;

    /** 地址列表: 默认地址排最前, 其余按创建时间倒序 */
    public List<UserAddress> list(Long userId) {
        return addressMapper.selectList(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getUserId, userId)
                        .orderByDesc(UserAddress::getIsDefault)
                        .orderByDesc(UserAddress::getCreateTime)
        );
    }

    /** 新增地址; 该用户还没有任何地址时, 自动把第一条设为默认, 方便首次结算直接选中 */
    public UserAddress add(UserAddress address) {
        Long count = addressMapper.selectCount(
                new LambdaQueryWrapper<UserAddress>().eq(UserAddress::getUserId, address.getUserId()));
        if (count == 0) {
            address.setIsDefault(1);
        }
        addressMapper.insert(address);
        return address;
    }

    /** 修改地址(只允许改收货人/电话/地址, 防止前端把 userId/isDefault 等一并改掉) */
    public void update(UserAddress address) {
        UserAddress upd = new UserAddress();
        upd.setId(address.getId());
        upd.setReceiverName(address.getReceiverName());
        upd.setReceiverPhone(address.getReceiverPhone());
        upd.setReceiverAddr(address.getReceiverAddr());
        addressMapper.updateById(upd);
    }

    /**
     * 设为默认地址: 先把该用户所有地址置为非默认, 再把指定地址设为默认。
     * 两步在同一事务内, 保证原子性。
     */
    @Transactional
    public void setDefault(Long id, Long userId) {
        UserAddress clear = new UserAddress();
        clear.setIsDefault(0);
        addressMapper.update(clear,
                new LambdaQueryWrapper<UserAddress>().eq(UserAddress::getUserId, userId));

        UserAddress set = new UserAddress();
        set.setId(id);
        set.setIsDefault(1);
        addressMapper.updateById(set);
    }

    /**
     * 删除地址(逻辑删除, 数据不物理消失)。
     * 若删除的是默认地址, 自动把该用户剩下的第一条地址设为默认。
     * @return false 表示地址不存在(由 Controller 转 404)
     */
    @Transactional
    public boolean delete(Long id, Long userId) {
        UserAddress addr = addressMapper.selectById(id);
        if (addr == null) {
            return false;
        }
        addressMapper.deleteById(id);

        if (addr.getIsDefault() != null && addr.getIsDefault() == 1) {
            UserAddress first = addressMapper.selectOne(
                    new LambdaQueryWrapper<UserAddress>()
                            .eq(UserAddress::getUserId, userId)
                            .orderByDesc(UserAddress::getCreateTime)
                            .last("LIMIT 1"));
            if (first != null) {
                UserAddress set = new UserAddress();
                set.setId(first.getId());
                set.setIsDefault(1);
                addressMapper.updateById(set);
            }
        }
        return true;
    }
}
