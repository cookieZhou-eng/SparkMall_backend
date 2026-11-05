package com.example.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.common.entity.ProductStock;
import com.example.product.mapper.ProductMapper;
import com.example.product.mapper.ProductStockMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.common.dto.SeckillConfirmRequest;
import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀服务: Redis 预热库存 + Lua 原子扣减 + DB 条件更新兜底 + 失败补偿回填。
 * 核心思想: 秒杀请求只打 Redis(高并发本身不丢给 DB), 真正扣减成功后异步落库。
 * 业务逻辑收敛在 Service, Controller 只做参数校验与 Result 封装。
 */
@Service
public class SeckillService {

    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String OPEN_KEY_PREFIX  = "seckill:open:";

    /**
     * Lua 原子扣减脚本: 读剩余库存 -> 判断是否够 -> 够则扣减返回1, 不够返回0。
     * "读+判+扣"三条指令由 Redis 单线程原子执行, 天然防超卖, 无需分布式锁。
     */
    private static final DefaultRedisScript<Long> SECKILL_DECR;
    static {
        SECKILL_DECR = new DefaultRedisScript<>();
        SECKILL_DECR.setScriptText(
                "local s = tonumber(redis.call('get', KEYS[1])) or 0 " +
                "if s >= tonumber(ARGV[1]) then " +
                "  redis.call('decrby', KEYS[1], ARGV[1]); return 1 " +
                "else return 0 end");
        SECKILL_DECR.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ProductStockMapper productStockMapper;
    @Resource
    private ProductMapper productMapper;
    @Resource
    private RedissonClient redissonClient;

    /**
     * ① 开卖前预热: 把 DB 可用库存灌进 Redis。
     * 预热用分布式锁保证同一商品只被一个实例预热, 避免并发重复覆盖。
     */
    public boolean prepare(Long productId) {
        RLock lock = redissonClient.getLock("seckill:prepare:" + productId);
        try {
            if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                return false;
            }
            try {
                ProductStock ps = productStockMapper.selectOne(
                        new LambdaQueryWrapper<ProductStock>().eq(ProductStock::getProductId, productId));
                if (ps == null || ps.getStock() == null) {
                    return false;
                }
                stringRedisTemplate.opsForValue().set(STOCK_KEY_PREFIX + productId, String.valueOf(ps.getStock()));
                stringRedisTemplate.opsForValue().set(OPEN_KEY_PREFIX + productId, "1"); // 标记开卖
                return true;
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * ② 秒杀扣减(高并发入口): Lua 原子扣 Redis 库存。只打 Redis, 不落库, 扛高 QPS。
     * @return 1 抢到(已扣Redis), 0 未抢到(已售罄/未开卖)
     */
    public boolean seckill(Long productId, int count) {
        if (!"1".equals(stringRedisTemplate.opsForValue().get(OPEN_KEY_PREFIX + productId))) {
            return false; // 未开卖
        }
        Long result = stringRedisTemplate.execute(
                SECKILL_DECR, List.of(STOCK_KEY_PREFIX + productId), String.valueOf(count));
        return result != null && result == 1L;
    }

    /**
     * ③ 落库兜底(抢到后异步/回调执行): DB 条件更新保证不超卖, 并累加销量。
     * 与秒杀扣减解耦: 即使 DB 慢, 也不阻塞 Redis 层的高并发扣减。
     */
    @Transactional
    public boolean persist(Long productId, int count) {
        int rows = productStockMapper.update(null, Wrappers.<ProductStock>lambdaUpdate()
                .eq(ProductStock::getProductId, productId)
                .apply("stock >= {0}", count)          // 条件更新兜底防超卖
                .setSql("stock = stock - {0}", count));
        if (rows > 0) {
            productMapper.update(null, Wrappers.<com.example.common.entity.Product>lambdaUpdate()
                    .eq(com.example.common.entity.Product::getId, productId)
                    .setSql("sales = sales + {0}", count));
            return true;
        }
        // 落库失败(兜底冲突/并发) -> 补偿回填 Redis, 保证两边一致不虚扣
        stringRedisTemplate.opsForValue().increment(STOCK_KEY_PREFIX + productId, count);
        return false;
    }

    /** ④ 秒杀订单确认(抢到资格后、落单前由 order-service Feign 调用)
     *  1) 一人一单幂等: 同一 user+product 只能下一单(利用 Redis setIfAbsent 原子性);
     *  2) DB 锁定库存(条件更新防超卖): locked += count 且 stock-locked >= count。
     *  后续订单的支付/超时/取消完全复用普通流程(deductStock / releaseStock)。 */
    public boolean confirmOrBind(SeckillConfirmRequest req) {
        // 幂等: 已下过单则该 key 已存在 -> setIfAbsent 返回 false, 拒绝重复秒杀
        boolean firstOrder = stringRedisTemplate.opsForValue().setIfAbsent(
                "seckill:order:" + req.getUserId() + ":" + req.getProductId(), "1");
        if (!firstOrder) {
            return false;
        }
        // DB 条件更新锁定库存(stock - locked >= count 防超卖), 与 Lua 预扣互为双保险
        boolean locked = productStockMapper.update(null, Wrappers.<ProductStock>lambdaUpdate()
                .eq(ProductStock::getProductId, req.getProductId())
                .apply("stock - locked >= {0}", req.getCount())
                .setSql("locked = locked + {0}", req.getCount())) > 0;
        if (!locked) {
            // 库存不足: 回撤幂等标记, 允许其他人继续抢
            stringRedisTemplate.delete("seckill:order:" + req.getUserId() + ":" + req.getProductId());
        }
        return locked;
    }

    /**
     * ⑤ 查询剩余库存(前端展示)。
     *
     * 注意: Redis 没有 key 只代表"还没预热", 不等于"库存为 0"。
     * 若直接返回 0, 前端会把所有未预热的商品显示成"已抢光"。
     * 因此这里按"开卖时触发预热"的设计做懒加载: 首次查询时从 DB 灌入 Redis, 之后走 Redis。
     */
    public Long remaining(Long productId) {
        String v = stringRedisTemplate.opsForValue().get(STOCK_KEY_PREFIX + productId);
        if (v != null) {
            return Long.parseLong(v);
        }
        // 未预热: 触发一次预热(内部用 Redisson 锁, 多实例并发下安全)
        prepare(productId);
        v = stringRedisTemplate.opsForValue().get(STOCK_KEY_PREFIX + productId);
        if (v != null) {
            return Long.parseLong(v);
        }
        // 预热仍未成功(该商品无库存记录, 或并发下没抢到锁): 降级读 DB 可用库存
        ProductStock ps = productStockMapper.selectOne(
                new LambdaQueryWrapper<ProductStock>().eq(ProductStock::getProductId, productId));
        if (ps == null || ps.getStock() == null) {
            return 0L;
        }
        int locked = ps.getLocked() == null ? 0 : ps.getLocked();
        return (long) Math.max(ps.getStock() - locked, 0);
    }
}