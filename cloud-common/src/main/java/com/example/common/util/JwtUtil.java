package com.example.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类(签发 + 解析)。
 *
 * 使用场景:
 *  - user-service: 登录成功后签发 JWT 返回给前端
 *  - gateway-service: 全局鉴权过滤器统一校验 JWT, 校验通过后把 userId/username 透传下游
 *
 * 设计说明:
 *  - 无状态: token 里含用户身份, 后端不需要存 session, 便于服务水平扩展
 *  - HS256 对称签名: user-service 与 gateway 必须共用同一把密钥(同值),
 *    密钥从环境变量 JWT_SECRET 或系统属性 jwt.secret 读取, 严禁硬编码进代码库
 *  - 过期时间: 默认 7 天, 前端 token 过期后需重新登录
 */
public final class JwtUtil {

    private JwtUtil() {
    }

    /** token 有效时长(毫秒): 7 天 */
    private static final long EXPIRE_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /**
     * HS256 对称密钥: 从环境变量 JWT_SECRET 或系统属性 jwt.secret 读取。
     * 长度必须 >= 256bit(32 字节), 未配置或过短直接启动失败, 避免使用弱密钥。
     */
    private static final SecretKey KEY = resolveKey();

    private static SecretKey resolveKey() {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            secret = System.getProperty("jwt.secret");
        }
        if (secret == null || secret.isBlank()) {
            secret = readClasspathSecret();
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "未配置 JWT 密钥: 请设置环境变量 JWT_SECRET(或系统属性 -Djwt.secret), "
                            + "或在 classpath 提供 jwt-secret.txt, 长度至少 32 字节");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "JWT 密钥长度不足: 至少 32 字节(256bit), 当前 " + bytes.length + " 字节");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    /** 本地开发兜底: 从 classpath 的 jwt-secret.txt 读取(该文件已被 .gitignore 忽略, 不随仓库上传) */
    private static String readClasspathSecret() {
        try (InputStream in = JwtUtil.class.getClassLoader().getResourceAsStream("jwt-secret.txt")) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static SecretKey key() {
        return KEY;
    }

    /**
     * 签发 JWT(不带角色, 兼容旧调用方, 默认 role=customer)
     * @param userId   用户 id(subject)
     * @param username 用户名(claim)
     */
    public static String createToken(Long userId, String username) {
        return createToken(userId, username, "customer");
    }

    /**
     * 签发 JWT(含角色)
     * @param userId   用户 id(subject)
     * @param username 用户名(claim)
     * @param role     角色 claim(customer/merchant), 网关鉴权通过后透传给下游做权限校验
     */
    public static String createToken(Long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + EXPIRE_MILLIS))
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析并校验 JWT
     * @return 校验通过返回 Claims; 签名错误/过期/格式非法返回 null
     */
    public static Claims parseToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            return null;
        }
    }
}