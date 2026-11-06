package com.example.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.dto.StockDeductRequest;
import com.example.common.entity.Category;
import com.example.common.entity.Product;
import com.example.common.entity.ProductStock;
import com.example.common.result.Result;
import com.example.product.mapper.CategoryMapper;
import com.example.product.mapper.ProductMapper;
import com.example.product.mapper.ProductStockMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品服务: 商品/分类/库存的业务逻辑 + Redis 缓存(Cache-Aside)。
 *
 * 分层说明:
 *  - 业务逻辑(含 Redis 缓存读写)收敛在本 Service, Controller 只做参数接收与 Result 封装。
 *  - order-service 通过 Feign 调用的库存接口也在这里实现, 保证"缓存/库存/销量"变更集中管理,
 *    数据一致性(扣库存后失效缓存)只维护一处。
 */
@Service
public class ProductService {

    @Resource
    private ProductMapper productMapper;
    @Resource
    private CategoryMapper categoryMapper;
    @Resource
    private ProductStockMapper productStockMapper;
    /** Redis 缓存: 商品详情/分类(读多写少, 降低数据库压力) */
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /** Redisson 分布式锁: 库存锁定/扣减高并发下做进程间互斥, 双重保障防超卖 */
    @Resource
    private RedissonClient redissonClient;
    /**
     * 商品/分类缓存 JSON 序列化。
     * 自建 ObjectMapper(注册 JSR310 处理 LocalDateTime 字段),
     * 不依赖 Spring 自动装配(Spring Boot 4 中该 starter 不提供 ObjectMapper bean)。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /** 商品详情缓存 key / 分类缓存 key */
    private static final String PRODUCT_DETAIL_KEY_PREFIX = "product:detail:";
    private static final String CATEGORIES_KEY = "product:categories";
    /** 空值占位: 缓存不存在的商品 id 用, 防止缓存穿透反复打库 */
    private static final String EMPTY_PLACEHOLDER = "\u0000NULL"; // JSON 不会出现的值, 见 writeEmpty 说明
    private static final Duration EMPTY_TTL = Duration.ofMinutes(2); // 空值占位存活很短, 避免长期占用

    /** 读缓存(Cache-Aside 第一步) */
    private String readCache(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /** 写缓存(Cache-Aside 第二步, 乐观地把 DB 数据回填 Redis) */
    private void writeCache(String key, Object data) {
        try {
            // 雪崩防护: TTL 随机化(3~10 分钟), 避免大批 key 在同一瞬间过期同时打爆 DB
            stringRedisTemplate.opsForValue().set(key, OBJECT_MAPPER.writeValueAsString(data), randomCacheTtl());
        } catch (Exception ignored) {
            // 缓存失败不影响主链路, 下次直接回源数据库即可
        }
    }

    /**
     * 穿透防护: DB 中不存在的 key 也写一个"短 TTL 空值占位"。
     * 这样恶意/不存在的 id 只打一次 DB, 后续全部命中占位直接返回 null, 不再穿透打到数据库。
     * 占位值用一个 JSON 反序列化后绝不等于的商品字符串(\u0000NULL)区分于真实数据。
     */
    private void writeEmpty(String key) {
        try {
            stringRedisTemplate.opsForValue().set(key, EMPTY_PLACEHOLDER, EMPTY_TTL);
        } catch (Exception ignored) {
        }
    }

    /** 雪崩防护: 返回 3~10 分钟之间的随机缓存有效期 */
    private Duration randomCacheTtl() {
        long minutes = 3 + ThreadLocalRandom.current().nextInt(8); // 3 ~ 10 取整分钟
        return Duration.ofMinutes(minutes);
    }

    /** 删除缓存(数据变更后主动失效, 保证读到最新值) */
    private void evictCache(String... keys) {
        stringRedisTemplate.delete(List.of(keys));
    }

    /**
     * 主动失效商品详情缓存(供商家商品管理使用: 编辑/上下架/删除后调用),
     * key 常量仍收敛在本类, 避免散落多处。
     */
    public void evictProductDetailCache(Long productId) {
        evictCache(PRODUCT_DETAIL_KEY_PREFIX + productId);
    }

    /**
     * 商品列表(支持按分类筛选 + 关键词搜索 + 分页 + 排序)
     * selectPage 会自动生成带 LIMIT 的 SQL, 同时返回总条数
     * 因实体上有 @TableLogic, MP 会自动追加 is_deleted=0 过滤
     *
     * @param sort 排序方式(可空): sales=销量倒序 / priceAsc=价格升序 / priceDesc=价格降序 /
     *             其他或空=按创建时间倒序
     */
    public Result.PageData<Product> list(Long categoryId, String keyword, Integer page, Integer size, String sort) {

        // LambdaQueryWrapper: 用方法引用构造 WHERE 条件, 编译期校验字段名, 避免硬编码字符串
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, 1);                              // 只查上架商品

        // 排序: 按前端传入的排序方式; 未指定时保持默认(创建时间倒序)
        if ("sales".equals(sort)) {
            wrapper.orderByDesc(Product::getSales);
        } else if ("priceAsc".equals(sort)) {
            wrapper.orderByAsc(Product::getPrice);
        } else if ("priceDesc".equals(sort)) {
            wrapper.orderByDesc(Product::getPrice);
        } else {
            wrapper.orderByDesc(Product::getCreateTime);
        }

        // 关键词搜索: 商品名称 / 副标题 模糊匹配(OR 组合, 需用 and 嵌套避免污染外层条件)
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Product::getName, kw)
                    .or().like(Product::getSubtitle, kw));
        }

        // 关键: 分类筛选时必须把"该分类 + 它的所有子分类"的商品一起查出来
        // 例: 商品挂在二级分类"手机"(4)/"电脑"(5)下, 用户点击一级分类"手机数码"(1)时,
        // 若只按 category_id = 1 精确匹配将查不到任何商品(这就是之前点分类无商品的原因)
        if (categoryId != null) {
            List<Long> ids = new ArrayList<>();
            collectCategoryIds(categoryId, ids);
            wrapper.in(Product::getCategoryId, ids);
        }

        Page<Product> pageResult = productMapper.selectPage(new Page<>(page, size), wrapper);
        return new Result.PageData<>(pageResult.getTotal(), pageResult.getRecords());
    }

    /**
     * 按商家查上架商品(公开, 商家主页使用):
     * 只查 status=1 上架且未删除(@TableLogic 自动过滤), 按创建时间倒序。
     */
    public List<Product> listByMerchant(Long merchantId) {
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getMerchantId, merchantId)
                .eq(Product::getStatus, 1)
                .orderByDesc(Product::getCreateTime));
    }

    /**
     * 批量查询商品(购物车列表/结算时由 order-service 通过 Feign 调用)。
     * 不限制上架状态: 已下架商品也要返回, 购物车据此标记"失效", 由前端提示用户移除。
     * 同时填充非数据库字段 stock(可用库存 = stock - locked), 供购物车展示与加购上限校验。
     */
    public List<Product> batch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Product> products = productMapper.selectList(
                new LambdaQueryWrapper<Product>().in(Product::getId, ids));
        Map<Long, ProductStock> stockMap = productStockMapper.selectList(
                        new LambdaQueryWrapper<ProductStock>().in(ProductStock::getProductId, ids))
                .stream()
                .collect(Collectors.toMap(ProductStock::getProductId, s -> s));
        products.forEach(p -> {
            ProductStock st = stockMap.get(p.getId());
            int available = st == null ? 0 : Math.max(st.getStock() - st.getLocked(), 0);
            p.setStock(available);
        });
        return products;
    }

    /**
     * 递归收集指定分类及其所有后代子分类的 id(含自身)
     * 即使传的是没有子分类的二级分类也能正确返回(集合只包含自己)
     * 支持无限级分类, 新增三级/四级分类无需改此处代码
     */
    private void collectCategoryIds(Long parentId, List<Long> result) {
        //从一级分类id，一次递归循环找出所有子类分类，最后筛选出所有子类进行分类分页。
        result.add(parentId);
        List<Category> children = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>().eq(Category::getParentId, parentId)
        );
        for (Category child : children) {
            collectCategoryIds(child.getId(), result);
        }
    }

    /**
     * 商品详情(Cache-Aside, 含 穿透/击穿/雪崩 三道防线)
     * 注意: 下单也走此接口取最新价格, 故 TTL 较短, 且支付扣库存后强制失效, 保证价格/销量及时更新
     */
    public Product getById(Long id) {
        String cacheKey = PRODUCT_DETAIL_KEY_PREFIX + id;
        // 1. 先读缓存
        String cached = readCache(cacheKey);
        if (cached != null) {
            if (EMPTY_PLACEHOLDER.equals(cached)) {
                return null; // 穿透防护: 命中空值占位, 说明 DB 也不存在, 直接返回 null, 不再打库
            }
            try {
                return OBJECT_MAPPER.readValue(cached, Product.class);
            } catch (Exception ignored) {
                // JSON 解析失败(如结构变更), 直接回源数据库
            }
        }

        // 2. 击穿防护: 热点 key 过期瞬间, 用互斥锁保证只有一个请求回源 DB 重建缓存,
        //    其它并发请求在锁外等待, 拿到锁后走 double-check 命中新缓存的 key, 避免同一时间全部打库。
        RLock lock = redissonClient.getLock("product:cache:lock:" + id);
        lock.lock();
        try {
            // double-check: 等待锁期间, 可能已有其它线程完成回填
            cached = readCache(cacheKey);
            if (cached != null) {
                if (EMPTY_PLACEHOLDER.equals(cached)) {
                    return null;
                }
                try {
                    return OBJECT_MAPPER.readValue(cached, Product.class);
                } catch (Exception ignored) {
                }
            }

            Product product = productMapper.selectById(id);
            if (product == null) {
                // 穿透防护: DB 也没有, 写一个短 TTL 空值占位, 避免同一不存在的 id 反复打库
                writeEmpty(cacheKey);
                return null;
            }
            // 雪崩防护: 回填时 TTL 已随机化(3~10 分钟)
            writeCache(cacheKey, product);
            return product;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 分类树(读多写少, 走 Redis 缓存, 全量数据一次缓存)
     * 注: selectList(null) 表示无条件查全部, MP 会自动过滤逻辑删除
     */
    public List<Category> categories() {
        String cached = readCache(CATEGORIES_KEY);
        if (cached != null) {
            try {
                return OBJECT_MAPPER.readValue(cached, new TypeReference<List<Category>>() {
                });
            } catch (Exception ignored) {
            }
        }

        List<Category> all = categoryMapper.selectList(null);
        // 找出一级分类
        List<Category> roots = all.stream()
                .filter(c -> c.getParentId() != null && c.getParentId() == 0L)
                .sorted(Comparator.comparing(Category::getSort))
                .collect(Collectors.toList());
        // 给每个一级分类挂上子分类
        roots.forEach(root -> root.setChildren(
                all.stream()
                        .filter(c -> c.getParentId() != null && c.getParentId().equals(root.getId()))
                        .sorted(Comparator.comparing(Category::getSort))
                        .collect(Collectors.toList())
        ));
        writeCache(CATEGORIES_KEY, roots);
        return roots;
    }

    /**
     * 锁定库存(下单未支付时由 order-service 通过 Feign 调用)
     *
     * 背景: t_product_stock 用 stock(可用库存) + locked(锁定库存) 两个字段配合。
     * 下单先"锁定"而非直接扣减, 这样商品虽然暂时不可再卖, 但一旦超时未支付
     * 释放锁定时又可重新售卖(不污染真实库存)。
     *
     * 原子锁定, 防超卖:
     *   UPDATE t_product_stock
     *   SET locked = locked + {count}
     *   WHERE product_id = ? AND (stock - locked) >= {count}
     * 影响行数为 0 说明"剩余可卖库存(stock - locked)"不足, 返回 false, 下单失败。
     * "判断 + 加锁"一步完成, 无先查再改, 天然避免并发超卖。
     */
    public boolean lockStock(StockDeductRequest req) {
        // 使用 lambda + 安全占位符, 避免手写字符串拼接 SQL 注入
//        LambdaUpdateWrapper<ProductStock> wrapper = Wrappers.lambdaUpdate();
//        wrapper.eq(ProductStock::getProductId, req.getProductId())
//                // 关键: 只有"剩余可用 = stock - locked"足够时才允许加锁, 防止超卖
//                .apply("stock - locked >= {0}", req.getCount())
//                .setSql("locked = locked + {0}", req.getCount());
//        return productStockMapper.update(null, wrapper) > 0;

        RLock lock = redissonClient.getLock("stock:lock:" + req.getProductId());
        try {
            // 拿锁等待最长 3 秒, 持锁最多 10 秒; 拿不到锁说明同商品并发锁定中, 快速失败避免堆积
            if(!lock.tryLock(3,10,TimeUnit.SECONDS)) {
                return false;
            }
            try {
                // 锁内再做原子条件更新，分布式锁 + DB条件更新双重防超卖。
                LambdaUpdateWrapper<ProductStock> wrapper = Wrappers.lambdaUpdate();
                wrapper.eq(ProductStock::getProductId,req.getProductId())
                        .apply("stock - locked >= {0}",req.getCount())
                        .setSql("locked = locked + {0}",req.getCount());
                return productStockMapper.update(null,wrapper) > 0;
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放锁定库存(订单超时未支付 / 用户取消订单时由 order-service 调用)
     *
     * 把之前锁定(locked)的数量释放回去, 让商品重新可卖:
     *   UPDATE t_product_stock SET locked = locked - {count}
     *   WHERE product_id = ? AND locked >= {count}
     * 加 locked >= count 条件做兜底, 避免并发释放把 locked 扣成负数。
     */
    public boolean releaseStock(StockDeductRequest req) {
//        LambdaUpdateWrapper<ProductStock> wrapper = Wrappers.lambdaUpdate();
//        wrapper.eq(ProductStock::getProductId, req.getProductId())
//                .apply("locked >= {0}", req.getCount())
//                .setSql("locked = locked - {0}", req.getCount());
//        return productStockMapper.update(null, wrapper) > 0;

        RLock lock = redissonClient.getLock("stock:lock:" + req.getProductId());
        try {
            if(!lock.tryLock(3,10,TimeUnit.SECONDS)) {
                return false;
            }
            try {
                LambdaUpdateWrapper<ProductStock> wrapper = Wrappers.lambdaUpdate();
                wrapper.eq(ProductStock::getProductId,req.getProductId())
                        .apply("locked >= {0}",req.getCount())
                        .setSql("locked = locked - {0}", req.getCount());
                return productStockMapper.update(null,wrapper) > 0;
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 退款回补库存(退款审核通过时由 order-service 调用)。
     *
     * 与 releaseStock 的区别:
     *   - releaseStock 只把 locked 减回去(适用于"下单未支付就取消");
     *   - restoreStock 是商品已真实卖出后退款, 需要把 stock 加回去, 同时回退销量。
     * 用分布式锁 + 原子 SQL, 避免与同商品的扣减/锁定并发冲突。
     */
    public boolean restoreStock(StockDeductRequest req) {
        if (req == null || req.getProductId() == null || req.getCount() == null || req.getCount() <= 0) {
            return false;
        }
        RLock lock = redissonClient.getLock("stock:lock:" + req.getProductId());
        try {
            if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                return false;
            }
            try {
                LambdaUpdateWrapper<ProductStock> wrapper = Wrappers.lambdaUpdate();
                wrapper.eq(ProductStock::getProductId, req.getProductId())
                        .setSql("stock = stock + {0}", req.getCount());
                int rows = productStockMapper.update(null, wrapper);
                if (rows > 0) {
                    // 退款后销量回退(GREATEST 防止出现负数)
                    productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                            .eq(Product::getId, req.getProductId())
                            .setSql("sales = GREATEST(sales - {0}, 0)", req.getCount()));
                    // 库存/销量变更, 主动失效商品详情缓存, 保证前端立即可见
                    evictCache(PRODUCT_DETAIL_KEY_PREFIX + req.getProductId(), CATEGORIES_KEY);
                }
                return rows > 0;
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 支付确认时的"最终扣减"(用户支付成功后由 order-service 调用)
     *
     * 支付成功意味着这部分库存真正卖出, 需要从"锁定"转为"已售":
     *   UPDATE t_product_stock
     *   SET stock = stock - {count}, locked = locked - {count}
     *   WHERE product_id = ? AND locked >= {count}
     * 即: 可用库存减少 count, 同时把之前锁定的 count 取消锁定,
     * 两部分同时减少, 保证"真实卖出量"与"锁定量"都不会虚增。
     */
    public boolean deductStock(StockDeductRequest req) {
        // 使用 lambda + 安全占位符, 避免手写字符串拼接 SQL 注入
//        LambdaUpdateWrapper<ProductStock> wrapper = Wrappers.lambdaUpdate();
//        wrapper.eq(ProductStock::getProductId, req.getProductId())
//                .apply("locked >= {0}", req.getCount())
//                .setSql("stock = stock - {0}, locked = locked - {0}", req.getCount());
//        int rows = productStockMapper.update(null, wrapper);
//        // 支付成功即真实卖出: 扣减库存成功的同时, 原子累加商品销量(sales)。
//        // 用 setSql("sales = sales + count") 让数据库端完成累加, 天然并发安全、不会丢更新。
//        if (rows > 0) {
//            productMapper.update(null, Wrappers.<Product>lambdaUpdate()
//                    .eq(Product::getId, req.getProductId())
//                    .setSql("sales = sales + {0}", req.getCount()));
//            // 销量/库存已变更, 主动失效该商品详情与分类缓存, 保证前端立即可见新销量
//            evictCache(PRODUCT_DETAIL_KEY_PREFIX + req.getProductId(), CATEGORIES_KEY);
//        }
//        return rows > 0;

        RLock lock = redissonClient.getLock("stock:lock:" + req.getProductId());
        try {
            if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                return false;
            }
            try {
                // 使用 lambda + 安全占位符, 避免手写字符串拼接 SQL 注入
                LambdaUpdateWrapper<ProductStock> wrapper = Wrappers.lambdaUpdate();
                wrapper.eq(ProductStock::getProductId, req.getProductId())
                        .apply("locked >= {0}", req.getCount())
                        .setSql("stock = stock - {0}, locked = locked - {0}", req.getCount());
                int rows = productStockMapper.update(null, wrapper);
                // 支付成功即真实卖出: 扣减库存成功的同时, 原子累加商品销量(sales)。
                // 用 setSql("sales = sales + count") 让数据库端完成累加, 天然并发安全、不会丢更新。
                if (rows > 0) {
                    productMapper.update(null, Wrappers.<Product>lambdaUpdate()
                            .eq(Product::getId, req.getProductId())
                            .setSql("sales = sales + {0}", req.getCount()));
                    // 销量/库存已变更, 主动失效该商品详情与分类缓存, 保证前端立即可见新销量
                    evictCache(PRODUCT_DETAIL_KEY_PREFIX + req.getProductId(), CATEGORIES_KEY);
                }
                return rows > 0;
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

