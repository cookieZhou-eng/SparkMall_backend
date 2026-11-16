package com.example.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.common.entity.Product;
import com.example.product.mapper.ProductMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商品服务内部业务(服务间调用, 不对外暴露)。
 */
@Service
public class InternalProductService {

    @Resource
    private ProductMapper productMapper;
    @Resource
    private ProductService productService;

    /**
     * 商家被禁用时级联下架其全部在售商品并失效详情缓存。
     * 重新启用商家后商品保持下架态, 由商家自行重新上架(上架仍需商品已过审)。
     *
     * @return 本次强制下架的商品数量
     */
    public int offShelfByMerchant(Long merchantId) {
        // 先查出在售商品 id 列表, 逐个失效详情缓存
        List<Long> onShelfIds = productMapper.selectList(
                        new LambdaQueryWrapper<Product>()
                                .eq(Product::getMerchantId, merchantId)
                                .eq(Product::getStatus, 1))
                .stream().map(Product::getId).toList();
        if (onShelfIds.isEmpty()) {
            return 0;
        }
        productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getMerchantId, merchantId)
                .eq(Product::getStatus, 1)
                .set(Product::getStatus, 0));
        onShelfIds.forEach(productService::evictProductDetailCache);
        return onShelfIds.size();
    }
}
