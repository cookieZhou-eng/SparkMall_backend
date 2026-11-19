package com.example.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.entity.Category;
import com.example.common.entity.Product;
import com.example.common.entity.ProductStock;
import com.example.common.result.Result;
import com.example.product.dto.MerchantProductRequest;
import com.example.product.dto.MerchantProductStatusRequest;
import com.example.product.exception.BusinessException;
import com.example.product.mapper.CategoryMapper;
import com.example.product.mapper.ProductMapper;
import com.example.product.mapper.ProductStockMapper;
import com.example.product.feign.UserFeignClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 商家商品服务: 商家对自己商品的增删改查(含上下架)业务逻辑。
 *
 * 分层说明:
 *  - 网关 /merchant/** 路由已校验 JWT 且 role=merchant 并透传 X-User-Id/X-User-Role,
 *    本 Service 仍对 role 做二次校验(双保险, 防止直连绕过网关)。
 *  - 商品归属校验: 所有写操作必须 merchant_id = 当前商家, 否则抛 BusinessException(403)。
 *  - 商家账号状态校验: 新增/编辑/上架前经 Feign 校验商家账号未被禁用
 *    (第二重保险: 正常情况下网关的账号状态拦截已挡掉被禁商家的请求)。
 *  - 新增商品 @Transactional 保证 t_product + t_product_stock 两条插入同成败;
 *    编辑/上下架/删除后主动失效商品详情缓存(复用 ProductService 的缓存 key)。
 */
@Slf4j
@Service
public class MerchantProductService {

    @Resource
    private ProductMapper productMapper;
    @Resource
    private CategoryMapper categoryMapper;
    @Resource
    private ProductStockMapper productStockMapper;
    @Resource
    private ProductService productService;
    @Resource
    private UserFeignClient userFeignClient;

    /**
     * 我的商品列表(含已下架), 按创建时间倒序。
     * 支持按商品名称模糊搜索 + 按分类筛选(分类包含其所有子分类)。
     */
    public Result.PageData<Product> listMyProducts(Long merchantId, String role, Integer page, Integer size,
                                                   String name, Long categoryId) {
        requireMerchant(role);
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .eq(Product::getMerchantId, merchantId);
        if (name != null && !name.isBlank()) {
            wrapper.like(Product::getName, name.trim());
        }
        if (categoryId != null) {
            List<Long> ids = new ArrayList<>();
            collectCategoryIds(categoryId, ids);
            wrapper.in(Product::getCategoryId, ids);
        }
        wrapper.orderByDesc(Product::getCreateTime);
        Page<Product> pageResult = productMapper.selectPage(new Page<>(page, size), wrapper);

        // 回填库存: t_product 不含库存字段, 单独按商品 id 批量查 t_product_stock
        List<Product> records = pageResult.getRecords();
        if (!records.isEmpty()) {
            List<Long> productIds = records.stream().map(Product::getId).toList();
            List<ProductStock> stocks = productStockMapper.selectList(
                    new LambdaQueryWrapper<ProductStock>().in(ProductStock::getProductId, productIds));
            java.util.Map<Long, Integer> stockMap = stocks.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            ProductStock::getProductId, ProductStock::getStock, (a, b) -> a));
            records.forEach(p -> p.setStock(stockMap.getOrDefault(p.getId(), 0)));
        }
        return new Result.PageData<>(pageResult.getTotal(), records);
    }

    /** 递归收集分类及其所有子分类 id(与 C 端商品列表的分类筛选口径一致) */
    private void collectCategoryIds(Long parentId, List<Long> result) {
        result.add(parentId);
        List<Category> children = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>().eq(Category::getParentId, parentId));
        for (Category child : children) {
            collectCategoryIds(child.getId(), result);
        }
    }

    /**
     * 新增商品: 校验分类/价格/库存 → 插入 t_product(下架+待审核+merchantId) → 插入 t_product_stock。
     * 事务保证商品与库存记录同成败(库存插入失败则商品也回滚)。
     *
     * 平台审核规则: 新增商品默认 status=0(下架) + auditStatus=0(待审核),
     * 必须由管理员审核通过后商家才能上架售卖。
     */
    @Transactional
    public Long createProduct(Long merchantId, String role, MerchantProductRequest req) {
        requireMerchant(role);
        requireMerchantEnabled(merchantId);
        validateRequest(req, false);

        Product product = new Product();
        product.setMerchantId(merchantId);
        product.setCategoryId(req.getCategoryId());
        product.setName(req.getName().trim());
        product.setSubtitle(req.getSubtitle());
        product.setMainImage(req.getMainImage());
        product.setDetailImages(req.getDetailImages());
        product.setPrice(req.getPrice());
        product.setOriginalPrice(req.getOriginalPrice());
        product.setSales(0);
        product.setStatus(0);       // 待审核期间强制下架
        product.setAuditStatus(0);  // 新增默认待审核, 审核通过后才允许上架
        productMapper.insert(product); // 自增主键回填 product.getId()

        ProductStock stock = new ProductStock();
        stock.setProductId(product.getId());
        stock.setStock(req.getStock());
        stock.setLocked(0);
        productStockMapper.insert(stock);
        return product.getId();
    }

    /**
     * 编辑商品: 校验归属 → 更新商品基本信息 → 可选更新库存(stock 非空时) → 失效详情缓存。
     * 走 selectById 拿到带 version 的实体后 updateById, 乐观锁自动生效。
     *
     * 平台审核规则: 商品内容(名称/图文/价格/分类)变更视为新内容, 重新进入待审核
     * (auditStatus=0), 同时强制下架(status=0), 审核通过前不可上架售卖, 防止绕过审核。
     */
    @Transactional
    public void updateProduct(Long merchantId, String role, MerchantProductRequest req) {
        requireMerchant(role);
        requireMerchantEnabled(merchantId);
        validateRequest(req, true);
        Product product = requireOwnedProduct(merchantId, req.getId());

        product.setCategoryId(req.getCategoryId());
        product.setName(req.getName().trim());
        product.setSubtitle(req.getSubtitle());
        product.setMainImage(req.getMainImage());
        product.setDetailImages(req.getDetailImages());
        product.setPrice(req.getPrice());
        product.setOriginalPrice(req.getOriginalPrice());
        // 内容变更 → 重新提审: 清空上次审核结论, 强制下架等待管理员复审;
        // 同时解除平台下架锁(内容已是新版本, 走新一轮审核流程)
        product.setAuditStatus(0);
        product.setAuditReason(null);
        product.setAuditTime(null);
        product.setAdminLocked(0);
        product.setStatus(0);
        productMapper.updateById(product);
        // MP updateById 默认忽略 null 字段, 审核备注/时间需用 UpdateWrapper 显式置 NULL
        productMapper.update(null,
                new LambdaUpdateWrapper<Product>()
                        .eq(Product::getId, product.getId())
                        .set(Product::getAuditReason, null)
                        .set(Product::getAuditTime, null));

        // 可选修改库存: stock 传入且 >=0 时覆盖可用库存; locked(未支付订单占用)不动,
        // 普通下单仍按 stock 扣减
        if (req.getStock() != null) {
            if (req.getStock() < 0) {
                throw new BusinessException(400, "库存不能小于 0");
            }
            productStockMapper.update(null,
                    new LambdaUpdateWrapper<ProductStock>()
                            .set(ProductStock::getStock, req.getStock())
                            .eq(ProductStock::getProductId, product.getId()));
        }

        // 名称/价格等已变更, 主动失效详情缓存, 保证 C 端立即读到最新数据
        productService.evictProductDetailCache(product.getId());
    }

    /**
     * 上架/下架切换: 校验归属 → 上架校验审核状态 → 更新 status → 失效详情缓存。
     *
     * 审核门禁: 只有 auditStatus=1(审核通过) 的商品才允许上架;
     * 待审核提示等待复审, 已拒绝时透出拒绝原因, 防止未审核商品流入市场。
     */
    public void updateStatus(Long merchantId, String role, Long productId, MerchantProductStatusRequest req) {
        requireMerchant(role);
        if (req == null || req.getStatus() == null || (req.getStatus() != 0 && req.getStatus() != 1)) {
            throw new BusinessException(400, "status 只能是 0(下架) 或 1(上架)");
        }
        Product product = requireOwnedProduct(merchantId, productId);
        // 上架属"扩大销售面"的写操作, 必须校验商家账号未被禁用(下架/查看不受限)
        if (req.getStatus() == 1) {
            requireMerchantEnabled(merchantId);
            // 平台强制下架的商品: 商家不能直接复售, 须编辑重新提审或联系管理员解锁
            if (Integer.valueOf(1).equals(product.getAdminLocked())) {
                throw new BusinessException(403,
                        "该商品已被平台下架，请编辑商品内容重新提交审核，审核通过后方可上架");
            }
        }
        if (req.getStatus() == 1 && !Integer.valueOf(1).equals(product.getAuditStatus())) {
            if (Integer.valueOf(2).equals(product.getAuditStatus())) {
                String reason = product.getAuditReason() == null || product.getAuditReason().isBlank()
                        ? "" : "，原因: " + product.getAuditReason();
                throw new BusinessException(400, "商品审核未通过" + reason + "，修改商品后重新提交审核");
            }
            throw new BusinessException(400, "商品正在等待管理员审核，审核通过后才能上架");
        }
        product.setStatus(req.getStatus());
        productMapper.updateById(product);
        // 下架/重新上架都失效详情缓存, C 端列表只查上架商品不受缓存影响
        productService.evictProductDetailCache(productId);
    }

    /**
     * 删除商品(逻辑删除): 校验归属 → 有锁定库存(locked>0, 有未支付订单占用)则拒绝 → 删除 → 失效缓存。
     */
    public void deleteProduct(Long merchantId, String role, Long productId) {
        requireMerchant(role);
        requireOwnedProduct(merchantId, productId);
        ProductStock stock = productStockMapper.selectOne(
                new LambdaQueryWrapper<ProductStock>().eq(ProductStock::getProductId, productId));
        if (stock != null && stock.getLocked() != null && stock.getLocked() > 0) {
            throw new BusinessException(400, "该商品有未支付订单占用, 暂时无法删除");
        }
        // 实体有 @TableLogic, deleteById 实际执行 UPDATE is_deleted=1
        productMapper.deleteById(productId);
        productService.evictProductDetailCache(productId);
    }

    /** 角色校验(业务侧双保险): 必须 merchant */
    private void requireMerchant(String role) {
        if (!"merchant".equals(role)) {
            throw new BusinessException(403, "无商家权限");
        }
    }

    /**
     * 校验商家账号未被禁用(Feign 调 user-service 内部接口)。
     * Feign 调用异常时放行并记日志(不因 user-service 抖动阻断正常商家),
     * 被禁商家的请求正常情况下已在网关被账号状态拦截, 此处为第二重保险。
     */
    private void requireMerchantEnabled(Long merchantId) {
        try {
            Boolean enabled = userFeignClient.merchantEnabled(merchantId).getData();
            if (!Boolean.TRUE.equals(enabled)) {
                throw new BusinessException(403, "商家账号已被禁用，无法操作商品");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("校验商家账号状态失败(merchantId={}): {}", merchantId, e.getMessage());
        }
    }

    /**
     * 校验商品存在且属于当前商家, 不满足抛 BusinessException("无权操作该商品")。
     */
    private Product requireOwnedProduct(Long merchantId, Long productId) {
        if (productId == null) {
            throw new BusinessException(400, "商品 id 不能为空");
        }
        Product product = productMapper.selectById(productId);
        if (product == null || !merchantId.equals(product.getMerchantId())) {
            throw new BusinessException(403, "无权操作该商品");
        }
        return product;
    }

    /**
     * 新增/编辑公共参数校验: 分类存在、价格合法、库存合法。
     * @param isUpdate true-编辑(校验 id) false-新增(校验 stock)
     */
    private void validateRequest(MerchantProductRequest req, boolean isUpdate) {
        if (req == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        if (isUpdate) {
            if (req.getId() == null) {
                throw new BusinessException(400, "商品 id 不能为空");
            }
        } else {
            if (req.getStock() == null || req.getStock() < 0) {
                throw new BusinessException(400, "库存不能为空且不能小于 0");
            }
        }
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException(400, "商品名称不能为空");
        }
        if (req.getCategoryId() == null
                || categoryMapper.selectById(req.getCategoryId()) == null) {
            throw new BusinessException(400, "分类不存在");
        }
        if (req.getPrice() == null || req.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "价格必须大于 0");
        }
        if (req.getOriginalPrice() != null && req.getOriginalPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(400, "原价不能小于 0");
        }
    }
}
