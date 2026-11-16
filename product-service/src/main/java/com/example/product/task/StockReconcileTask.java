package com.example.product.task;

import com.example.common.entity.ProductStock;
import com.example.product.mapper.ProductStockMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 秒杀库存对账任务。
 *
 * 解决的痛点: 秒杀扣减走 Redis, 落库走 DB, 两个存储之间存在时间窗口。
 * 若服务在"Lua 扣减成功、DB 锁定失败"之间宕机, 或补偿回填失败,
 * Redis 库存就会与 DB 实际可用库存产生偏差, 导致"少卖"(Redis 显示售罄但 DB 有货)
 * 或"超卖风险"(Redis 有货但 DB 已锁定完)。人工核对成本高且不及时。
 *
 * 这里以 DB 为准(DB 是最终事实来源), 每 5 分钟自动校准一次 Redis 中的秒杀库存,
 * 把人工对账变成后台自动收敛, 保证两端最终一致。
 */
@Component
public class StockReconcileTask {

    // 日志记录器，用于记录对账过程中的关键事件
    private static final Logger log = LoggerFactory.getLogger(StockReconcileTask.class);
    private static final String STOCK_KEY_PREFIX = "seckill:stock:";

    @Resource
    private ProductStockMapper productStockMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 启动 1 分钟后开始, 之后每 5 分钟执行一次 */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void reconcile() {
        List<ProductStock> stocks = productStockMapper.selectList(null);
        int fixedCount = 0;
        for (ProductStock ps : stocks) {
            String key = STOCK_KEY_PREFIX + ps.getProductId();
            String cached = stringRedisTemplate.opsForValue().get(key);
            // 没有缓存 key 说明该商品未参与秒杀/未预热, 不属于对账范围
            if (cached == null) {
                continue;
            }
            int locked = ps.getLocked() == null ? 0 : ps.getLocked();
            long dbAvailable = Math.max(ps.getStock() - locked, 0);
            long cachedValue;
            try {
                cachedValue = Long.parseLong(cached);
            } catch (NumberFormatException e) {
                cachedValue = -1;
            }
            if (cachedValue != dbAvailable) {
                stringRedisTemplate.opsForValue().set(key, String.valueOf(dbAvailable));
                fixedCount++;
                log.warn("秒杀库存对账修正: productId={}, redis={}, db={}",
                        ps.getProductId(), cached, dbAvailable);
            }
        }
        if (fixedCount > 0) {
            log.info("秒杀库存对账完成, 本次修正 {} 个商品", fixedCount);
        }
    }
}
