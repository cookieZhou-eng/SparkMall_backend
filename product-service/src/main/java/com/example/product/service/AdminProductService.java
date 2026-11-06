package com.example.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.entity.Product;
import com.example.common.result.Result;
import com.example.product.exception.BusinessException;
import com.example.product.mapper.ProductMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端商品管理业务。
 *
 * 分层说明:
 *  - Controller 只做参数接收与 Result 封装。
 *  - 网关已校验 role=admin 并透传 X-User-Role, 这里再校验一次(双保险)。
 *  - 上下架后主动失效商品详情缓存, 保证顾客端立即读到最新状态。
 */
@Service
public class AdminProductService {

    private static final int MAX_PAGE_SIZE = 100;

    @Resource
    private ProductMapper productMapper;
    @Resource
    private ProductService productService;

    /**
     * 全量商品分页(含下架商品), 支持关键词/状态/商家筛选
     */
    public Result.PageData<Product> list(String role, String keyword, Integer status,
                                         Long merchantId, Integer page, Integer size) {
        checkAdmin(role);
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, MAX_PAGE_SIZE);

        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            wrapper.and(w -> w.like(Product::getName, kw)
                    .or().like(Product::getSubtitle, kw));
        }
        if (status != null) {
            wrapper.eq(Product::getStatus, status);
        }
        if (merchantId != null) {
            wrapper.eq(Product::getMerchantId, merchantId);
        }
        wrapper.orderByDesc(Product::getCreateTime);

        Page<Product> pageResult = productMapper.selectPage(new Page<>(p, s), wrapper);
        return new Result.PageData<>(pageResult.getTotal(), pageResult.getRecords());
    }

    /**
     * 商品统计(数据看板用): 总数、上架数、下架数
     */
    public Map<String, Long> count(String role) {
        checkAdmin(role);
        Map<String, Long> data = new LinkedHashMap<>();
        data.put("totalProducts", productMapper.selectCount(null));
        data.put("onShelfProducts", productMapper.selectCount(
                new LambdaQueryWrapper<Product>().eq(Product::getStatus, 1)));
        data.put("offShelfProducts", productMapper.selectCount(
                new LambdaQueryWrapper<Product>().eq(Product::getStatus, 0)));
        return data;
    }

    /**
     * 管理端强制上架/下架
     */
    public void updateStatus(String role, Long id, Integer status) {
        checkAdmin(role);
        if (id == null || status == null || (status != 0 && status != 1)) {
            throw new BusinessException(400, "商品ID或状态参数不合法");
        }
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(404, "商品不存在");
        }
        productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                .eq(Product::getId, id)
                .set(Product::getStatus, status));
        // 上下架后删除详情缓存, 顾客端下次读取直接回源最新状态
        productService.evictProductDetailCache(id);
    }

    private void checkAdmin(String role) {
        if (!"admin".equals(role)) {
            throw new BusinessException(403, "无管理员权限");
        }
    }
}
