package com.example.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.dto.MerchantBriefDTO;
import com.example.common.entity.Product;
import com.example.common.result.Result;
import com.example.product.exception.BusinessException;
import com.example.product.feign.UserFeignClient;
import com.example.product.mapper.ProductMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端商品管理业务(含商品审核)。
 *
 * 分层说明:
 *  - Controller 只做参数接收与 Result 封装。
 *  - 网关已校验 role=admin 并透传 X-User-Role, 这里再校验一次(双保险)。
 *  - 上下架/审核后主动失效商品详情缓存, 保证顾客端立即读到最新状态。
 *
 * 审核规则(平台硬性要求):
 *  - 商家新增/编辑的商品默认 audit_status=0 待审核, 只有审核通过(audit_status=1)才允许上架;
 *  - 管理端强制上架同样要求商品已过审, 拒绝审核时商品强制下架。
 */
@Slf4j
@Service
public class AdminProductService {

    private static final int MAX_PAGE_SIZE = 100;

    @Resource
    private ProductMapper productMapper;
    @Resource
    private ProductService productService;
    @Resource
    private UserFeignClient userFeignClient;

    /**
     * 全量商品分页(含下架商品), 支持关键词/上架状态/审核状态/商家筛选, 回填商家店铺名。
     */
    public Result.PageData<Product> list(String role, String keyword, Integer status, Integer auditStatus,
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
        if (auditStatus != null) {
            wrapper.eq(Product::getAuditStatus, auditStatus);
        }
        if (merchantId != null) {
            wrapper.eq(Product::getMerchantId, merchantId);
        }
        wrapper.orderByDesc(Product::getCreateTime);

        Page<Product> pageResult = productMapper.selectPage(new Page<>(p, s), wrapper);
        fillMerchantShopNames(pageResult.getRecords());
        return new Result.PageData<>(pageResult.getTotal(), pageResult.getRecords());
    }

    /**
     * 商品统计(数据看板用): 总数、上架数、下架数、待审核数、审核拒绝数
     */
    public Map<String, Long> count(String role) {
        checkAdmin(role);
        Map<String, Long> data = new LinkedHashMap<>();
        data.put("totalProducts", productMapper.selectCount(null));
        data.put("onShelfProducts", productMapper.selectCount(
                new LambdaQueryWrapper<Product>().eq(Product::getStatus, 1)));
        data.put("offShelfProducts", productMapper.selectCount(
                new LambdaQueryWrapper<Product>().eq(Product::getStatus, 0)));
        data.put("pendingAuditProducts", productMapper.selectCount(
                new LambdaQueryWrapper<Product>().eq(Product::getAuditStatus, 0)));
        data.put("rejectedAuditProducts", productMapper.selectCount(
                new LambdaQueryWrapper<Product>().eq(Product::getAuditStatus, 2)));
        return data;
    }

    /**
     * 商品审核: 通过(1) / 拒绝(2)。
     *  - 通过: 记录审核时间与操作备注, 商品仍保持当前上下架状态(商家自行上架), 并解除平台下架锁;
     *  - 拒绝: 必须填写原因, 商品强制下架(audit_status=2 本就不允许上架, 这里兜底保证在售商品被拒后立即下架)。
     */
    public void audit(String role, Long id, Integer auditStatus, String auditReason) {
        checkAdmin(role);
        if (id == null || auditStatus == null || (auditStatus != 1 && auditStatus != 2)) {
            throw new BusinessException(400, "审核参数不合法");
        }
        String reason = auditReason == null ? "" : auditReason.trim();
        if (auditStatus == 2 && reason.isEmpty()) {
            throw new BusinessException(400, "拒绝审核时必须填写原因");
        }
        if (reason.length() > 200) {
            throw new BusinessException(400, "审核原因不能超过 200 字");
        }
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(404, "商品不存在");
        }
        if (Integer.valueOf(1).equals(product.getAuditStatus()) && auditStatus == 1) {
            throw new BusinessException(400, "该商品已审核通过, 无需重复审核");
        }
        productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                .eq(Product::getId, id)
                .set(Product::getAuditStatus, auditStatus)
                .set(Product::getAuditReason, auditStatus == 2 ? reason : null)
                .set(Product::getAuditTime, LocalDateTime.now())
                // 平台下架锁与审核结论同步: 通过/拒绝后锁失去意义, 统一解除
                // (拒绝时 audit_status=2 本身就禁止上架, 不会因解锁而放行)
                .set(Product::getAdminLocked, 0)
                // 拒绝时兜底强制下架, 防止被拒商品仍在售
                .set(auditStatus == 2, Product::getStatus, 0));
        // 审核结论变更, 失效详情缓存保证 C 端立即可见
        productService.evictProductDetailCache(id);
    }

    /**
     * 管理端强制上架/下架。
     *  - 强制上架门禁: 商品必须审核通过(audit_status=1), 未过审商品不允许进入市场;
     *  - 强制下架: 置"平台下架锁"(admin_locked=1), 商家端不能再直接重新上架,
     *    须商家编辑重新提审(过审自动解锁)或管理员强制上架(解锁), 防止商家秒速复活被下架商品。
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
        if (status == 1 && !Integer.valueOf(1).equals(product.getAuditStatus())) {
            throw new BusinessException(400, "该商品尚未通过平台审核, 不能上架");
        }
        productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                .eq(Product::getId, id)
                .set(Product::getStatus, status)
                // 下架→加锁(商家不可自行复售); 上架→解锁(管理员已放行)
                .set(Product::getAdminLocked, status == 0 ? 1 : 0));
        // 上下架后删除详情缓存, 顾客端下次读取直接回源最新状态
        productService.evictProductDetailCache(id);
    }

    /**
     * 按当前页商品里的商家 id 批量回填店铺名(Feign 调 user-service 内部接口)。
     * 回填失败不影响列表主链路, 仅记录警告日志。
     */
    private void fillMerchantShopNames(List<Product> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> merchantIds = records.stream()
                .map(Product::getMerchantId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (merchantIds.isEmpty()) {
            return;
        }
        try {
            List<MerchantBriefDTO> merchants = userFeignClient
                    .merchantBatch(merchantIds.stream().map(String::valueOf)
                            .collect(Collectors.joining(",")))
                    .getData();
            if (merchants == null) {
                return;
            }
            Map<Long, MerchantBriefDTO> merchantMap = merchants.stream()
                    .collect(Collectors.toMap(MerchantBriefDTO::getId, Function.identity(), (a, b) -> a));
            records.forEach(p -> {
                MerchantBriefDTO m = p.getMerchantId() == null ? null : merchantMap.get(p.getMerchantId());
                if (m != null) {
                    p.setMerchantShopName(m.getShopName() != null && !m.getShopName().isBlank()
                            ? m.getShopName() : m.getNickname());
                }
            });
        } catch (Exception e) {
            log.warn("回填商家店铺名失败, 商品列表将缺少店铺名列: {}", e.getMessage());
        }
    }

    private void checkAdmin(String role) {
        if (!"admin".equals(role)) {
            throw new BusinessException(403, "无管理员权限");
        }
    }
}
