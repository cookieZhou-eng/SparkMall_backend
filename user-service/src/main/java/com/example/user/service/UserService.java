package com.example.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.common.entity.User;
import com.example.common.util.JwtUtil;
import com.example.user.dto.PasswordChangeRequest;
import com.example.user.dto.PasswordResetRequest;
import com.example.user.dto.ProfileUpdateRequest;
import com.example.user.dto.RegisterRequest;
import com.example.user.dto.SmsSendRequest;
import com.example.user.dto.UserVO;
import com.example.user.exception.BusinessException;
import com.example.user.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 用户服务: 登录 / 注册 / 个人中心 / 短信验证码 / 商家信息 业务逻辑。
 *
 * 分层说明:
 *  - 业务(BCrypt 校验 / JWT 签发 / 黑名单写入 / Redis 验证码)收敛在本 Service,
 *    Controller 只做参数接收与 Result 封装。
 *  - 业务失败抛 BusinessException(code, message), 由全局异常处理器统一转成 Result。
 *  - 退出登录: 把 token 写入 Redis 黑名单, TTL = token 剩余有效时长; 网关鉴权时命中黑名单即拒绝。
 */
@Slf4j
@Service
public class UserService {

    /** JWT 黑名单 key 前缀(与网关 GlobalAuthFilter 保持一致) */
    private static final String TOKEN_BLACKLIST_PREFIX = "auth:token:blacklist:";

    /** 短信验证码 key 前缀: 完整 key = sms:code:{phone}, TTL 5 分钟, 校验通过后立即删除(一次性) */
    private static final String SMS_CODE_PREFIX = "sms:code:";
    /** 短信发送频率限制 key: 60 秒内不允许重复发送 */
    private static final String SMS_LIMIT_PREFIX = "sms:limit:";
    private static final Duration SMS_CODE_TTL = Duration.ofMinutes(5);
    private static final Duration SMS_LIMIT_TTL = Duration.ofSeconds(60);

    /** 简单手机号格式校验: 1 开头 + 10 位数字 */
    private static final String PHONE_REGEX = "^1\\d{10}$";

    @Resource
    private UserMapper userMapper;
    /** 注意: 字段名须为 stringRedisTemplate, 按名注入才匹配 StringRedisTemplate 类型 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    /** 验证码随机数源(6 位数字) */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 登录: 按用户名查 t_user → 校验状态/密码(BCrypt) → 签发 JWT(含角色)。
     * @return token、昵称与角色(前端据 role 决定展示顾客/商家视图)
     */
    public Map<String, String> login(String username, String password) {
        // 用 LambdaQueryWrapper 按用户名查询, MP 自动过滤 is_deleted=0
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null) {
            throw new BusinessException(401, "用户不存在");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(403, "账号已被禁用");
        }
        // BCrypt 校验: 第一个参数是明文, 第二个是密文
        if (!PASSWORD_ENCODER.matches(password, user.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        Map<String, String> data = new HashMap<>();
        String role = user.getRole() == null ? "customer" : user.getRole();
        data.put("token", JwtUtil.createToken(user.getId(), user.getUsername(), role));
        data.put("nickname", user.getNickname());
        data.put("role", role);
        return data;
    }

    /**
     * 获取当前登录用户信息, 密码/逻辑删除字段置空防止泄露。
     */
    public User info(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(401, "用户不存在");
        }
        user.setPassword(null);
        user.setIsDeleted(null);
        return user;
    }

    /**
     * 退出登录: 把当前 token 加入 Redis 黑名单。
     * 黑名单键过期时间 = token 剩余有效时长, 到期后由 Redis 自动清理, 无需定时任务。
     * token 非法/已过期时静默忽略, 保证退出操作幂等。
     */
    public void logout(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return;
        }
        String token = auth.substring(7);
        Claims claims = JwtUtil.parseToken(token);
        if (claims == null) {
            return;
        }
        long ttl = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (ttl > 0) {
            stringRedisTemplate.opsForValue().set(
                    TOKEN_BLACKLIST_PREFIX + token, "1", ttl, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 解析当前用户 id: 优先网关透传头(已校验过 JWT, 业务侧信任上游), 其次 JWT 兜底。
     * @return 解析失败返回 null
     */
    public Long resolveUserId(String xUserId, String auth) {
        // 1. 网关统一鉴权后透传的头, 直接信任
        if (xUserId != null && !xUserId.isBlank()) {
            try {
                return Long.valueOf(xUserId.trim());
            } catch (NumberFormatException ignored) {
                // 非法头, 走 JWT 兜底
            }
        }
        // 2. 兜底: 从 Bearer JWT 解析
        if (auth != null && auth.startsWith("Bearer ")) {
            Claims claims = JwtUtil.parseToken(auth.substring(7));
            if (claims != null) {
                try {
                    return Long.valueOf(claims.getSubject());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    // ==================== V2 个人中心 + 商家模块 ====================

    /**
     * 注册: 用户名/手机号唯一校验 → 角色与店铺信息校验 → BCrypt 加密落库。
     * @return 注册成功的用户 id
     */
    public Long register(RegisterRequest req) {
        // 1. 基础参数校验
        String username = trim(req.getUsername());
        String password = req.getPassword();
        String phone = trim(req.getPhone());
        String role = req.getRole() == null ? "customer" : trim(req.getRole());
        if (username == null || username.isBlank()) {
            throw new BusinessException(400, "用户名不能为空");
        }
        if (password == null || password.length() < 6) {
            throw new BusinessException(400, "密码不能为空且长度至少 6 位");
        }
        if (phone == null || !phone.matches(PHONE_REGEX)) {
            throw new BusinessException(400, "手机号格式不正确");
        }
        if (!"customer".equals(role) && !"merchant".equals(role)) {
            throw new BusinessException(400, "角色只能是 customer 或 merchant");
        }
        if ("merchant".equals(role) && (req.getShopName() == null || req.getShopName().isBlank())) {
            throw new BusinessException(400, "商家注册必须填写店铺名");
        }
        // 2. 唯一性校验(用户名/手机号, DB 层还有唯一索引兜底)
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
            throw new BusinessException(400, "用户名已被占用");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, phone)) > 0) {
            throw new BusinessException(400, "手机号已被注册");
        }
        // 3. BCrypt 加密后插入
        User user = new User();
        user.setUsername(username);
        user.setPassword(PASSWORD_ENCODER.encode(password));
        user.setPhone(phone);
        user.setNickname(username);
        user.setRole(role);
        if ("merchant".equals(role)) {
            user.setShopName(req.getShopName().trim());
            user.setShopDesc(req.getShopDesc());
        }
        user.setStatus(1);
        userMapper.insert(user);
        return user.getId();
    }

    /**
     * 个人资料: 实体转脱敏 VO(不含 password/isDeleted)。
     */
    public UserVO profile(Long userId) {
        return toVO(requireUser(userId));
    }

    /**
     * 更新个人资料: 仅允许修改"非必要字段"(昵称/邮箱/头像/性别/店铺信息),
     * username/phone/role/password 不在此接口可改范围(安全考虑)。
     * @return 更新后的最新 profile
     */
    public UserVO updateProfile(Long userId, ProfileUpdateRequest req) {
        requireUser(userId);
        // 只 set 允许修改的字段, 非必要字段变更频率低, 顺带把更新时间交给 DB ON UPDATE 维护
        LambdaUpdateWrapper<User> wrapper = Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(req.getNickname() != null, User::getNickname, req.getNickname())
                .set(req.getEmail() != null, User::getEmail, req.getEmail())
                .set(req.getAvatar() != null, User::getAvatar, req.getAvatar())
                .set(req.getGender() != null, User::getGender, req.getGender())
                .set(req.getShopName() != null, User::getShopName, req.getShopName())
                .set(req.getShopDesc() != null, User::getShopDesc, req.getShopDesc());
        userMapper.update(null, wrapper);
        return profile(userId);
    }

    /**
     * 修改密码(已登录): 旧密码正确 + 新密码长度 >= 6 → BCrypt 加密更新。
     */
    public void changePassword(Long userId, PasswordChangeRequest req) {
        User user = requireUser(userId);
        if (req.getOldPassword() == null || req.getNewPassword() == null) {
            throw new BusinessException(400, "旧密码与新密码不能为空");
        }
        if (req.getNewPassword().length() < 6) {
            throw new BusinessException(400, "新密码长度至少 6 位");
        }
        if (!PASSWORD_ENCODER.matches(req.getOldPassword(), user.getPassword())) {
            throw new BusinessException(400, "旧密码不正确");
        }
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getPassword, PASSWORD_ENCODER.encode(req.getNewPassword())));
    }

    /**
     * 发送短信验证码(模拟): 60 秒频率限制(setIfAbsent) + 生成 6 位数字验证码存 Redis 5 分钟。
     * 验证码只打日志不返回(模拟环境); 手机号必须是已注册用户, 防止被恶意轰炸任意号码。
     */
    public void sendSms(SmsSendRequest req) {
        String phone = trim(req == null ? null : req.getPhone());
        if (phone == null || !phone.matches(PHONE_REGEX)) {
            throw new BusinessException(400, "手机号格式不正确");
        }
        // 手机号必须是已注册用户
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {
            throw new BusinessException(400, "该手机号尚未注册");
        }
        // 60 秒发送间隔: setIfAbsent 原子占位, 已存在说明间隔内重复发送
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                SMS_LIMIT_PREFIX + phone, "1", SMS_LIMIT_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(429, "发送太频繁，请 60 秒后再试");
        }
        // 生成 6 位数字验证码, TTL 5 分钟
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        stringRedisTemplate.opsForValue().set(SMS_CODE_PREFIX + phone, code, SMS_CODE_TTL);
        // 模拟短信: 只打日志, 接口不返回验证码
        log.info("【模拟短信】手机号 {} 验证码 {}", phone, code);
    }

    /**
     * 忘记密码重置(未登录): 校验短信验证码(一次性) → 按手机号找到用户更新密码。
     */
    public void resetPassword(PasswordResetRequest req) {
        String phone = trim(req.getPhone());
        String code = trim(req.getCode());
        if (phone == null || code == null || req.getNewPassword() == null || req.getNewPassword().length() < 6) {
            throw new BusinessException(400, "参数不完整, 新密码长度至少 6 位");
        }
        String cacheKey = SMS_CODE_PREFIX + phone;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached == null || !cached.equals(code)) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
        // 验证通过立即删除, 保证一次性使用
        stringRedisTemplate.delete(cacheKey);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {
            throw new BusinessException(400, "该手机号尚未注册");
        }
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getPassword, PASSWORD_ENCODER.encode(req.getNewPassword())));
    }

    /** 商家搜索最多返回条数 */
    private static final int MERCHANT_SEARCH_LIMIT = 20;

    /**
     * 商家搜索(公开): role=merchant 且 用户名/店铺名/店铺简介 LIKE 关键词, 最多 20 条;
     * keyword 为空时返回前 20 个商家(便于首页"逛店铺")。
     */
    public List<UserVO> searchMerchants(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(User::getRole, "merchant")
                .orderByDesc(User::getCreateTime)
                .last("LIMIT " + MERCHANT_SEARCH_LIMIT);
        if (!kw.isEmpty()) {
            wrapper.and(w -> w.like(User::getUsername, kw)
                    .or().like(User::getShopName, kw)
                    .or().like(User::getShopDesc, kw));
        }
        return userMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    /**
     * 公开查看商家信息: 仅允许查看 role=merchant 的用户。
     * 附带累计销售额(totalSales): 订单数据在 order 库, 学习项目单库直查(t_order_item JOIN t_order),
     * 微服务规范做法应走 Feign 调 order-service 聚合, 此处权衡简化(见 UserMapper 注释)。
     */
    public UserVO merchantInfo(Long merchantId) {
        User user = requireUser(merchantId);
        if (!"merchant".equals(user.getRole())) {
            throw new BusinessException(404, "商家不存在");
        }
        UserVO vo = toVO(user);
        vo.setTotalSales(userMapper.sumMerchantTotalSales(merchantId));
        return vo;
    }

    /** 按 id 查用户, 不存在抛业务异常 */
    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(401, "用户不存在");
        }
        return user;
    }

    /** 实体转脱敏 VO: 不含 password/isDeleted 等敏感或内部字段 */
    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setAvatar(user.getAvatar());
        vo.setGender(user.getGender());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setShopName(user.getShopName());
        vo.setShopDesc(user.getShopDesc());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }

    /** 去空格, null 安全 */
    private String trim(String s) {
        return s == null ? null : s.trim();
    }
}
