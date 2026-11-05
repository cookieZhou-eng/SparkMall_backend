-- ============================================================================
-- 星火商城 SparkMall 数据库初始化脚本
-- 适用 MySQL 8.0+ (字符集 utf8mb4, 存储引擎 InnoDB)
-- 设计理念:
--   1. 微服务场景下不使用外键, 数据一致性由应用层 + 分布式事务保证
--   2. 通用审计字段: create_time / update_time
--   3. 逻辑删除字段 is_deleted: 高频更新表(如商品/订单)避免物理删除影响性能与回溯
--   4. 乐观锁字段 version: 高并发场景下扣减库存/订单状态机使用, 防止脏写
--   5. 金额一律使用 decimal(10,2), 严禁 float/double, 避免精度丢失
--   6. 库存表独立: 便于后期用 Redis 缓存预热 + Lua 原子扣减
-- ============================================================================

-- 创建数据库
-- 简化: 只指定 CHARACTER SET, 不指定 COLLATE, 让 MySQL 用该字符集的默认 collation
-- 这样兼容所有版本(5.x / 8.0 / MariaDB), 不再因为 collation 名差异报错
DROP DATABASE IF EXISTS `spark_mall`;
CREATE DATABASE `spark_mall` CHARACTER SET utf8mb4;
USE `spark_mall`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;  -- 关闭外键检查(微服务下不用外键, 关闭以提升导入性能)

-- ----------------------------------------------------------------------------
-- 1. 用户表 t_user
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_user`;
CREATE TABLE `t_user` (
    `id`            BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`      VARCHAR(50)  NOT NULL COMMENT '用户名(登录名)',
    `password`      VARCHAR(100) NOT NULL COMMENT '密码(BCrypt 加密后的密文, 严禁明文)',
    `nickname`      VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `phone`         VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `email`         VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `avatar`        VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    `gender`        TINYINT(1)   DEFAULT 0 COMMENT '性别: 0-未知 1-男 2-女',
    `status`        TINYINT(1)   DEFAULT 1 COMMENT '状态: 0-禁用 1-启用',
    `role`          VARCHAR(20)  NOT NULL DEFAULT 'customer' COMMENT '角色: customer顾客/merchant商家',
    `shop_name`     VARCHAR(64)  DEFAULT NULL COMMENT '店铺名(商家)',
    `shop_desc`     VARCHAR(255) DEFAULT NULL COMMENT '店铺简介(商家)',
    -- 逻辑删除: 0-未删除 1-已删除. 查询时一律带 is_deleted = 0 过滤, 配合 MyBatis-Plus @TableLogic 自动处理
    `is_deleted`    TINYINT(1)   DEFAULT 0 COMMENT '逻辑删除标记: 0-未删除 1-已删除',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),        -- 用户名唯一
    UNIQUE KEY `uk_phone` (`phone`),              -- 手机号唯一
    KEY `idx_status_deleted` (`status`, `is_deleted`)  -- 复合索引: 后台常用查询条件
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- ----------------------------------------------------------------------------
-- 2. 商品分类表 t_category
--    采用 parent_id 自关联实现"无限级"分类树, 查询时通过递归/自连接拼装
--    (简单场景推荐最多3级, 复杂分类可用闭包表或路径枚举法)
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_category`;
CREATE TABLE `t_category` (
    `id`          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    `parent_id`   BIGINT(20)   DEFAULT 0 COMMENT '父分类ID, 0表示顶级分类',
    `name`        VARCHAR(50)  NOT NULL COMMENT '分类名称',
    `sort`        INT(11)      DEFAULT 0 COMMENT '排序(同级内排序, 越小越靠前)',
    `icon`        VARCHAR(255) DEFAULT NULL COMMENT '分类图标URL', 
    `status`      TINYINT(1)   DEFAULT 1 COMMENT '状态: 0-禁用 1-启用',
    `is_deleted`  TINYINT(1)   DEFAULT 0 COMMENT '逻辑删除标记',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_parent_status` (`parent_id`, `status`)  -- 查询子分类常用
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品分类表';

-- ----------------------------------------------------------------------------
-- 3. 商品表 t_product
--    SPU 级别信息(同类商品共享一份商品信息)
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_product`;
CREATE TABLE `t_product` (
    `id`            BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `category_id`   BIGINT(20)    NOT NULL COMMENT '所属分类ID',
    `name`          VARCHAR(100)  NOT NULL COMMENT '商品名称',
    `subtitle`      VARCHAR(200)  DEFAULT NULL COMMENT '副标题',
    `main_image`    VARCHAR(255)  DEFAULT NULL COMMENT '主图URL',
    `detail_images` VARCHAR(1000) DEFAULT NULL COMMENT '详情图URL列表, 逗号分隔',
    `price`         DECIMAL(10,2) NOT NULL COMMENT '销售价(元)',
    `original_price` DECIMAL(10,2) DEFAULT NULL COMMENT '原价(元), 用于划线展示',
    `sales`         INT(11)       DEFAULT 0 COMMENT '总销量',
    `status`        TINYINT(1)    DEFAULT 1 COMMENT '上架状态: 0-下架 1-上架',
    `merchant_id`   BIGINT(20)    DEFAULT NULL COMMENT '商家用户ID',
    -- 乐观锁版本号: 更新时 WHERE version = old_version, 防止并发修改覆盖
    `version`       INT(11)       DEFAULT 0 COMMENT '乐观锁版本号',
    `is_deleted`    TINYINT(1)    DEFAULT 0 COMMENT '逻辑删除标记',
    `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_category_status` (`category_id`, `status`, `is_deleted`),
    KEY `idx_sales` (`sales`)  -- 按销量排序查询使用
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品表';

-- ----------------------------------------------------------------------------
-- 4. 商品库存表 t_product_stock
--    库存独立成表的原因:
--    a) 库存字段更新极其频繁, 与商品低频更新字段分离, 减少行锁竞争
--    b) 便于后续将库存预热到 Redis, 用 Lua 脚本原子扣减后再异步回写 DB
--    c) 支持多仓库/多规格扩展(sku_id)
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_product_stock`;
CREATE TABLE `t_product_stock` (
    `id`          BIGINT(20)  NOT NULL AUTO_INCREMENT COMMENT '主键',
    `product_id`  BIGINT(20)  NOT NULL COMMENT '商品ID',
    `stock`       INT(11)     NOT NULL DEFAULT 0 COMMENT '可用库存',
    `locked`      INT(11)     NOT NULL DEFAULT 0 COMMENT '锁定库存(下单未支付时锁定, 防超卖)',
    -- 可用库存 = stock - locked, 扣减时需保证 stock - locked >= 购买数量
    `version`     INT(11)     NOT NULL DEFAULT 0 COMMENT '乐观锁版本号(扣减库存时使用)',
    `update_time` DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_id` (`product_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品库存表';

-- ----------------------------------------------------------------------------
-- 5. 订单表 t_order
--    订单号 order_no 设计为业务主键(便于对外暴露, 不暴露自增ID)
--    订单状态机: 1-待支付 2-已支付 3-已发货 4-已签收 5-已取消
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_order`;
CREATE TABLE `t_order` (
    `id`            BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '订单主键',
    `order_no`      VARCHAR(32)   NOT NULL COMMENT '订单号(业务唯一, 建议雪花算法生成)',
    `user_id`       BIGINT(20)    NOT NULL COMMENT '下单用户ID',
    `total_amount`  DECIMAL(10,2) NOT NULL COMMENT '订单总金额(商品实付)',
    `total_count`   INT(11)       NOT NULL COMMENT '商品总数量',
    `status`        TINYINT(2)    NOT NULL DEFAULT 1 COMMENT '订单状态: 1-待支付 2-已支付 3-已发货 4-已签收 5-已取消',
    `receiver_name` VARCHAR(50)  DEFAULT NULL COMMENT '收货人姓名',
    `receiver_phone` VARCHAR(20) DEFAULT NULL COMMENT '收货人电话',
    `receiver_addr` VARCHAR(255) DEFAULT NULL COMMENT '收货地址',
    `remark`        VARCHAR(255) DEFAULT NULL COMMENT '订单备注',
    `pay_time`      DATETIME      DEFAULT NULL COMMENT '支付时间',
    `version`       INT(11)       DEFAULT 0 COMMENT '乐观锁版本号(订单状态机变更时使用)',
    `is_deleted`    TINYINT(1)    DEFAULT 0 COMMENT '逻辑删除标记',
    `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间(下单时间)',
    `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),            -- 订单号唯一
    KEY `idx_user_status` (`user_id`, `status`, `is_deleted`)  -- 用户查询自己的订单
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单表';

-- ----------------------------------------------------------------------------
-- 6. 订单详情表 t_order_item
--    订单快照: 下单瞬间的商品名称/价格/快照都存进来, 即使商品后续改价或删除也不影响历史订单
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_order_item`;
CREATE TABLE `t_order_item` (
    `id`            BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_id`      BIGINT(20)    NOT NULL COMMENT '订单ID',
    `order_no`      VARCHAR(32)   NOT NULL COMMENT '订单号(冗余, 便于反查)',
    `product_id`    BIGINT(20)    NOT NULL COMMENT '商品ID',
    `product_name`  VARCHAR(100)  NOT NULL COMMENT '商品名称快照',
    `product_image` VARCHAR(255)  DEFAULT NULL COMMENT '商品主图快照',
    `price`         DECIMAL(10,2) NOT NULL COMMENT '购买单价快照',
    `count`         INT(11)       NOT NULL COMMENT '购买数量',
    `subtotal`      DECIMAL(10,2) NOT NULL COMMENT '小计金额(price * count)',
    `merchant_id`      BIGINT(20)   DEFAULT NULL COMMENT '商家用户ID(下单时从商品快照)',
    `logistics_status` TINYINT(2)   NOT NULL DEFAULT 0 COMMENT '物流状态: 0-待发货 1-已发货 2-已送达',
    `ship_time`        DATETIME     DEFAULT NULL COMMENT '发货时间',
    `delivery_time`    DATETIME     DEFAULT NULL COMMENT '送达时间',
    `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_merchant_id` (`merchant_id`)  -- 商家查"我卖出的订单"按明细分页
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单详情表';

-- ----------------------------------------------------------------------------
-- 7. 支付流水表 t_payment
--    独立支付流水表的原因:
--    a) 第三方支付(支付宝/微信)的回调可能重复到达, 用流水号幂等控制
--    b) 一次订单可能多次支付尝试(失败/取消), 每次都产生一条流水
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_payment`;
CREATE TABLE `t_payment` (
    `id`            BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT '主键',
    `payment_no`    VARCHAR(32)   NOT NULL COMMENT '支付流水号(内部生成, 雪花算法)',
    `order_no`      VARCHAR(32)   NOT NULL COMMENT '关联订单号',
    `user_id`       BIGINT(20)    NOT NULL COMMENT '用户ID',
    `amount`        DECIMAL(10,2) NOT NULL COMMENT '支付金额',
    `channel`       VARCHAR(20)   NOT NULL COMMENT '支付渠道: alipay-支付宝 wechat-微信 mock-模拟',
    `status`        TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '支付状态: 0-待支付 1-成功 2-失败 3-已退款',
    `trade_no`      VARCHAR(64)   DEFAULT NULL COMMENT '第三方交易号(支付宝/微信返回)',
    `pay_time`      DATETIME      DEFAULT NULL COMMENT '支付完成时间',
    `create_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_payment_no` (`payment_no`),  -- 内部流水号唯一
    KEY `idx_order_no` (`order_no`),             -- 按订单号查支付记录
    KEY `idx_trade_no` (`trade_no`)              -- 第三方回调按交易号反查
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付流水表';


-- ============================================================================
-- 初始数据(便于前端联调直接看到效果)
-- 密码均为 123456, 经过 BCrypt 加密后的密文(开发环境用, 生产环境务必由注册接口生成)
-- ============================================================================
INSERT INTO `t_user` (`username`, `password`, `nickname`, `phone`, `gender`, `status`)
VALUES
    ('admin', '$2a$10$tlLvflh0I9zXrFcp3pjlf.odrDxO0TKmoB7eNr4XIbVXzRVFwv70i', '管理员', '13800000000', 1, 1),
    ('zhangsan', '$2a$10$tlLvflh0I9zXrFcp3pjlf.odrDxO0TKmoB7eNr4XIbVXzRVFwv70i', '张三', '13800000001', 1, 1),
    ('lisi', '$2a$10$tlLvflh0I9zXrFcp3pjlf.odrDxO0TKmoB7eNr4XIbVXzRVFwv70i', '李四', '13800000002', 2, 1);

-- 分类(两级, 显式指定 ID 避免嵌套子查询在某些 MySQL 客户端报错:
-- "You can't specify target table 't_category' for update in FROM clause")
INSERT INTO `t_category` (`id`, `parent_id`, `name`, `sort`) VALUES
    (1, 0, '手机数码', 1),
    (2, 0, '家用电器', 2),
    (3, 0, '服饰鞋包', 3),
    (4, 1, '手机', 1),
    (5, 1, '电脑', 2),
    (6, 2, '大家电', 1),
    (7, 2, '厨电', 2),
    (8, 3, '男装', 1),
    (9, 3, '女装', 2);

-- 商品(8 个示例)
INSERT INTO `t_product` (`category_id`, `name`, `subtitle`, `main_image`, `price`, `original_price`, `sales`, `status`) VALUES
    (4, '星火手机 Pro', '徕卡四摄 旗舰芯片', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=smartphone%20flagship%20product%20photo%20clean%20background&image_size=square', 4999.00, 5499.00, 1280, 1),
    (4, '星火手机 Lite', '高性价比 5000mAh 大电池', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=smartphone%20lite%20product%20photo%20clean%20background&image_size=square', 1999.00, 2199.00, 860, 1),
    (5, '星火笔记本 Air', '13寸轻薄本 16G+512G', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=laptop%20air%20slim%20notebook%20product%20photo&image_size=square', 6999.00, 7299.00, 320, 1),
    (5, '星火游戏本 Pro', 'RTX 显卡 240Hz 高刷屏', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=gaming%20laptop%20rgb%20keyboard%20product%20photo&image_size=square', 9999.00, 10999.00, 210, 1),
    (6, '星火冰箱 470L', '风冷无霜 一级能效', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=refrigerator%20modern%20product%20photo%20clean&image_size=square', 3299.00, 3599.00, 158, 1),
    (6, '星火滚筒洗衣机', '10KG 洗烘一体 变频', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=washing%20machine%20front%20load%20product%20photo&image_size=square', 2599.00, 2899.00, 230, 1),
    (7, '星火电饭煲 4L', 'IH 电磁加热 智能预约', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=rice%20cooker%20modern%20product%20photo&image_size=square', 399.00, 499.00, 680, 1),
    (8, '星火纯棉T恤', '100% 新疆棉 透气舒适', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=plain%20white%20t-shirt%20product%20photo%20clean&image_size=square', 99.00, 159.00, 1500, 1);

-- 库存(每个商品一份库存记录, 简化处理, 不分多仓)
INSERT INTO `t_product_stock` (`product_id`, `stock`, `locked`) VALUES
    (1, 500, 0),
    (2, 1000, 0),
    (3, 300, 0),
    (4, 200, 0),
    (5, 150, 0),
    (6, 180, 0),
    (7, 800, 0),
    (8, 2000, 0);

-- ============================================================================
-- 扩展测试数据(15 个新商品, 覆盖全部分类)
-- 说明:
--   1. 显式指定 id 从 101 开始, 避免与既有 1~8 冲突, 也便于与库存记录一一对应
--   2. 每个新商品同步给一条库存记录, 否则后续做下单功能时新商品会无库存可扣
--   3. 图片 URL 使用在线文生图接口, 便于前端直接展示
-- ============================================================================
INSERT INTO `t_product` (`id`, `category_id`, `name`, `subtitle`, `main_image`, `price`, `original_price`, `sales`, `status`) VALUES
    (101, 4, '星火折叠屏手机', '折叠大屏 旗舰影像', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=foldable%20smartphone%20product%20photo%20clean%20background&image_size=square', 8999.00, 9999.00, 342, 1),
    (102, 4, '星火平板 Pro', '11寸 2.5K 高刷屏', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=tablet%20device%20product%20photo%20clean%20background&image_size=square', 2999.00, 3299.00, 520, 1),
    (103, 5, '星火电竞显示器 27寸', '2K 165Hz 快速响应', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=gaming%20monitor%20product%20photo%20clean&image_size=square', 1599.00, 1899.00, 780, 1),
    (104, 5, '星火机械键盘 87键', '三模热插拔 RGB 背光', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=mechanical%20keyboard%20rgb%20product%20photo&image_size=square', 299.00, 399.00, 1230, 1),
    (105, 5, '星火无线鼠标', '轻量化 2.4G 静音', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=wireless%20mouse%20product%20photo%20clean&image_size=square', 129.00, 199.00, 2100, 1),
    (106, 6, '星火空调 1.5匹', '新一级能效 变频冷暖', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=air%20conditioner%20indoor%20unit%20product%20photo&image_size=square', 2699.00, 2999.00, 430, 1),
    (107, 6, '星火电视 65寸', '4K 全面屏 MEMC', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=smart%20tv%20living%20room%20product%20photo&image_size=square', 3999.00, 4599.00, 280, 1),
    (108, 6, '星火扫地机器人', '激光导航 自动集尘', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=robot%20vacuum%20cleaner%20product%20photo&image_size=square', 2499.00, 2999.00, 360, 1),
    (109, 7, '星火微波炉 23L', '变频微波 光波烧烤', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=microwave%20oven%20product%20photo%20clean&image_size=square', 499.00, 599.00, 960, 1),
    (110, 7, '星火空气炸锅 5L', '无油低脂 可视窗口', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=air%20fryer%20kitchen%20product%20photo&image_size=square', 299.00, 399.00, 1500, 1),
    (111, 7, '星火破壁机 1.75L', '静音低噪 智能预约', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=blender%20kitchen%20product%20photo%20clean&image_size=square', 399.00, 499.00, 1100, 1),
    (112, 8, '星火休闲裤', '直筒版型 弹力面料', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=men%20casual%20trousers%20product%20photo&image_size=square', 139.00, 199.00, 1880, 1),
    (113, 8, '星火连帽卫衣', '加绒保暖 宽松百搭', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=men%20hoodie%20sweatshirt%20product%20photo&image_size=square', 169.00, 239.00, 2050, 1),
    (114, 9, '星火针织连衣裙', '收腰显瘦 温柔风', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=women%20knit%20dress%20product%20photo&image_size=square', 199.00, 299.00, 2400, 1),
    (115, 9, '星火雪纺衬衫', '垂坠透气 通勤必备', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=women%20chiffon%20shirt%20product%20photo&image_size=square', 159.00, 229.00, 1760, 1);

-- 新增商品的配套库存
INSERT INTO `t_product_stock` (`product_id`, `stock`, `locked`) VALUES
    (101, 300, 0),
    (102, 500, 0),
    (103, 400, 0),
    (104, 800, 0),
    (105, 1200, 0),
    (106, 350, 0),
    (107, 200, 0),
    (108, 260, 0),
    (109, 600, 0),
    (110, 900, 0),
    (111, 700, 0),
    (112, 1000, 0),
    (113, 1100, 0),
    (114, 1300, 0),
    (115, 900, 0);

-- 示例订单 + 详情(用于联调)
INSERT INTO `t_order` (`order_no`, `user_id`, `total_amount`, `total_count`, `status`, `receiver_name`, `receiver_phone`, `receiver_addr`) VALUES
    ('SP2026081800001', 2, 5098.00, 2, 2, '张三', '13800000001', '北京市海淀区中关村大街1号'),
    ('SP2026081800002', 3, 99.00, 1, 1, '李四', '13800000002', '上海市浦东新区张江高科技园区');

INSERT INTO `t_order_item` (`order_id`, `order_no`, `product_id`, `product_name`, `product_image`, `price`, `count`, `subtotal`) VALUES
    (1, 'SP2026081800001', 1, '星火手机 Pro', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=smartphone%20flagship%20product%20photo%20clean%20background&image_size=square', 4999.00, 1, 4999.00),
    (1, 'SP2026081800001', 7, '星火电饭煲 4L', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=rice%20cooker%20modern%20product%20photo&image_size=square', 99.00, 1, 99.00),
    (2, 'SP2026081800002', 8, '星火纯棉T恤', 'https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=plain%20white%20t-shirt%20product%20photo%20clean&image_size=square', 99.00, 1, 99.00);

INSERT INTO `t_payment` (`payment_no`, `order_no`, `user_id`, `amount`, `channel`, `status`, `trade_no`, `pay_time`) VALUES
    ('PAY20260818000001', 'SP2026081800001', 2, 5098.00, 'mock', 1, 'MOCK_TRADE_001', '2026-08-18 10:30:00');

-- ----------------------------------------------------------------------------
-- 8. 收货地址表 t_user_address
--    独立管理收货地址, 结算时从列表选择, 避免每次手动填写
--    is_default: 1=默认地址(结算时自动选中) 0=非默认; 每个用户默认地址唯一
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `t_user_address`;
CREATE TABLE `t_user_address` (
    `id`             BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        BIGINT(20)   NOT NULL COMMENT '用户ID',
    `receiver_name`  VARCHAR(50)  NOT NULL COMMENT '收货人姓名',
    `receiver_phone` VARCHAR(20)  NOT NULL COMMENT '收货人电话',
    `receiver_addr`  VARCHAR(255) NOT NULL COMMENT '收货地址',
    `is_default`     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否默认地址: 1-是 0-否',
    `is_deleted`     TINYINT(1)   DEFAULT 0 COMMENT '逻辑删除标记',
    `create_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '收货地址表';

-- 示例地址数据(用户1两条, 便于测试"选择地址"与"默认地址"逻辑)
INSERT INTO `t_user_address` (`user_id`, `receiver_name`, `receiver_phone`, `receiver_addr`, `is_default`) VALUES
    (1, '测试员', '13800000000', '北京市海淀区中关村软件园8号楼', 1),
    (1, '星火用户', '13900000000', '上海市浦东新区张江高科', 0),
    (2, '张三', '13800000001', '北京市海淀区中关村大街1号', 1),
    (3, '李四', '13800000002', '上海市浦东新区张江高科技园区', 1);

SET FOREIGN_KEY_CHECKS = 1;

-- ============ V2 增量：个人中心 + 商家模块 (2026-11) ============
-- 说明: 针对已初始化过旧版本库的增量升级脚本;
--       全新安装直接跑上面的 CREATE TABLE(已包含这些字段), 再执行本段会报"字段已存在", 忽略即可。
ALTER TABLE `t_user` ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT 'customer' COMMENT '角色:customer顾客/merchant商家';
ALTER TABLE `t_user` ADD COLUMN `shop_name` VARCHAR(64) NULL COMMENT '店铺名(商家)';
ALTER TABLE `t_user` ADD COLUMN `shop_desc` VARCHAR(255) NULL COMMENT '店铺简介(商家)';
ALTER TABLE `t_product` ADD COLUMN `merchant_id` BIGINT NULL COMMENT '商家用户ID';

-- ============ V3 增量：商家卖出订单 + 物流状态 + 总销售额 (2026-11) ============
-- 说明: 针对已初始化过旧版本库的增量升级脚本;
--       全新安装直接跑上面的 CREATE TABLE(已包含这些字段), 再执行本段会报"字段已存在", 忽略即可。
-- 明细级物流: 同一订单可含多个商家的商品, 发货/送达以"订单明细"为单位流转。
ALTER TABLE `t_order_item` ADD COLUMN `merchant_id` BIGINT NULL COMMENT '商家用户ID(下单时从商品快照)';
ALTER TABLE `t_order_item` ADD COLUMN `logistics_status` TINYINT NOT NULL DEFAULT 0 COMMENT '物流状态:0待发货/1已发货/2已送达';
ALTER TABLE `t_order_item` ADD COLUMN `ship_time` DATETIME NULL COMMENT '发货时间';
ALTER TABLE `t_order_item` ADD COLUMN `delivery_time` DATETIME NULL COMMENT '送达时间';
ALTER TABLE `t_order_item` ADD INDEX `idx_merchant_id` (`merchant_id`);
