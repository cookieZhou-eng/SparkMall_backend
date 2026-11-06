package com.example.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.common.entity.Cart;
import com.example.common.entity.Product;
import com.example.common.result.Result;
import com.example.order.dto.CartAddRequest;
import com.example.order.dto.CartItemVO;
import com.example.order.dto.CartUpdateRequest;
import com.example.order.exception.BusinessException;
import com.example.order.feign.ProductFeignClient;
import com.example.order.mapper.CartMapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 购物车业务(从 localStorage 迁移到后端)。
 *
 * 设计要点:
 *  - t_cart 只存商品ID与数量, 名称/价格/图片/库存实时从 product-service 批量查询,
 *    避免商品改价后购物车展示旧价格;
 *  - 同一用户同一商品唯一(user_id + product_id), 重复加购累加数量, 且不超过可用库存;
 *  - 已下架/已删除商品仍会返回, 但 valid=false, 由前端提示用户移除;
 *  - 结算仍走现有 /order/create(前端只提交勾选商品), 下单成功后按商品ID清理购物车。
 */
@Service
public class CartService {

    private static final int MAX_COUNT_PER_ITEM = 999;

    @Resource
    private CartMapper cartMapper;
    @Resource
    private ProductFeignClient productFeignClient;

    /**
     * 加入购物车: 已存在则数量累加, 校验商品上架与可用库存。
     */
    @Transactional
    public CartItemVO add(Long userId, CartAddRequest req) {
        checkLogin(userId);
        if (req == null || req.getProductId() == null) {
            throw new BusinessException(400, "商品ID不能为空");
        }
        int addCount = req.getCount() == null ? 1 : req.getCount();
        if (addCount < 1) {
            throw new BusinessException(400, "加购数量必须大于 0");
        }

        Product product = loadProduct(req.getProductId());
        if (product.getStatus() == null || product.getStatus() != 1) {
            throw new BusinessException(400, "商品已下架");
        }
        int available = product.getStock() == null ? 0 : product.getStock();

        Cart existing = cartMapper.selectOne(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getProductId, req.getProductId()));
        int newCount = (existing == null ? 0 : existing.getCount()) + addCount;
        if (newCount > available) {
            throw new BusinessException(400, "库存不足, 当前最多可购买 " + available + " 件");
        }
        if (newCount > MAX_COUNT_PER_ITEM) {
            throw new BusinessException(400, "单件商品最多购买 " + MAX_COUNT_PER_ITEM + " 件");
        }

        if (existing == null) {
            Cart cart = new Cart();
            cart.setUserId(userId);
            cart.setProductId(req.getProductId());
            cart.setCount(newCount);
            cart.setChecked(1);
            try {
                cartMapper.insert(cart);
                existing = cart;
            } catch (DuplicateKeyException e) {
                // 并发加购(如快速双击)时唯一索引兜底: 重新读取并累加, 避免直接报 500
                Cart concurrent = cartMapper.selectOne(new LambdaQueryWrapper<Cart>()
                        .eq(Cart::getUserId, userId)
                        .eq(Cart::getProductId, req.getProductId()));
                if (concurrent == null) {
                    throw e;
                }
                int merged = concurrent.getCount() + addCount;
                if (merged > available) {
                    throw new BusinessException(400, "库存不足, 当前最多可购买 " + available + " 件");
                }
                concurrent.setCount(merged);
                cartMapper.updateById(concurrent);
                existing = concurrent;
            }
        } else {
            existing.setCount(newCount);
            cartMapper.updateById(existing);
        }
        return toVO(existing, product);
    }

    /**
     * 购物车列表: 批量查询商品, 避免 N+1。
     */
    public List<CartItemVO> list(Long userId) {
        checkLogin(userId);
        List<Cart> carts = cartMapper.selectList(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId)
                .orderByDesc(Cart::getUpdateTime));
        if (carts.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = carts.stream().map(Cart::getProductId).distinct().toList();
        Map<Long, Product> productMap = batchProducts(productIds);
        return carts.stream()
                .map(c -> toVO(c, productMap.get(c.getProductId())))
                .toList();
    }

    /**
     * 更新购物车行(数量 / 勾选状态), 数量不能超过当前可用库存。
     */
    public CartItemVO update(Long userId, Long id, CartUpdateRequest req) {
        checkLogin(userId);
        if (id == null) {
            throw new BusinessException(400, "购物车行ID不能为空");
        }
        if (req == null) {
            throw new BusinessException(400, "更新参数不能为空");
        }
        Cart cart = cartMapper.selectById(id);
        if (cart == null) {
            throw new BusinessException(404, "购物车商品不存在");
        }
        if (!userId.equals(cart.getUserId())) {
            throw new BusinessException(403, "无权操作该购物车商品");
        }

        Product product = loadProduct(cart.getProductId());
        if (req.getCount() != null) {
            if (req.getCount() < 1 || req.getCount() > MAX_COUNT_PER_ITEM) {
                throw new BusinessException(400, "购买数量不合法");
            }
            int available = product.getStock() == null ? 0 : product.getStock();
            if (req.getCount() > available) {
                throw new BusinessException(400, "库存不足, 当前最多可购买 " + available + " 件");
            }
            cart.setCount(req.getCount());
        }
        if (req.getChecked() != null) {
            if (req.getChecked() != 0 && req.getChecked() != 1) {
                throw new BusinessException(400, "勾选状态不合法");
            }
            cart.setChecked(req.getChecked());
        }
        cartMapper.updateById(cart);
        return toVO(cart, product);
    }

    /**
     * 删除单行
     */
    public void delete(Long userId, Long id) {
        checkLogin(userId);
        Cart cart = cartMapper.selectById(id);
        if (cart == null) {
            return;
        }
        if (!userId.equals(cart.getUserId())) {
            throw new BusinessException(403, "无权操作该购物车商品");
        }
        cartMapper.deleteById(id);
    }

    /**
     * 清空购物车:
     *  - 传 productIds 时只删这些商品(下单成功后按已购商品清理);
     *  - 不传时清空当前用户全部购物车。
     */
    public void clear(Long userId, List<Long> productIds) {
        checkLogin(userId);
        LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<Cart>().eq(Cart::getUserId, userId);
        if (productIds != null && !productIds.isEmpty()) {
            wrapper.in(Cart::getProductId, productIds);
        }
        cartMapper.delete(wrapper);
    }

    /**
     * 全选 / 取消全选
     */
    public void checkAll(Long userId, Integer checked) {
        checkLogin(userId);
        if (checked == null || (checked != 0 && checked != 1)) {
            throw new BusinessException(400, "勾选状态不合法");
        }
        cartMapper.update(null, Wrappers.<Cart>lambdaUpdate()
                .eq(Cart::getUserId, userId)
                .set(Cart::getChecked, checked));
    }

    /**
     * 购物车商品总件数(用于角标)
     */
    public Long count(Long userId) {
        checkLogin(userId);
        List<Cart> carts = cartMapper.selectList(
                new LambdaQueryWrapper<Cart>().eq(Cart::getUserId, userId));
        return carts.stream().mapToLong(c -> c.getCount() == null ? 0 : c.getCount()).sum();
    }

    /**
     * 通过 Feign 批量查商品; 结果转 Map 便于按商品ID匹配。
     * 商品服务异常时返回空 Map, 购物车列表仍可展示(标记为失效), 不阻断用户操作。
     */
    private Map<Long, Product> batchProducts(List<Long> productIds) {
        Result<List<Product>> result = productFeignClient.batch(productIds);
        if (result == null || result.getData() == null) {
            return Map.of();
        }
        return result.getData().stream()
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
    }

    private Product loadProduct(Long productId) {
        Map<Long, Product> map = batchProducts(List.of(productId));
        Product product = map.get(productId);
        if (product == null) {
            throw new BusinessException(404, "商品不存在");
        }
        return product;
    }

    private CartItemVO toVO(Cart cart, Product product) {
        CartItemVO vo = new CartItemVO();
        vo.setId(cart.getId());
        vo.setProductId(cart.getProductId());
        vo.setCount(cart.getCount());
        vo.setChecked(cart.getChecked());
        boolean valid = product != null && product.getStatus() != null && product.getStatus() == 1;
        vo.setValid(valid);
        if (product != null) {
            vo.setName(product.getName());
            vo.setMainImage(product.getMainImage());
            vo.setPrice(product.getPrice());
            vo.setStock(product.getStock() == null ? 0 : product.getStock());
        } else {
            vo.setName("商品已失效");
            vo.setStock(0);
        }
        return vo;
    }

    private void checkLogin(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
    }
}
