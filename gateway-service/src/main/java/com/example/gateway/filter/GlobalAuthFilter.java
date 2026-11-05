package com.example.gateway.filter;

import com.example.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关统一鉴权 Filter(JWT + Redis 黑名单)。
 *
 * 设计要点:
 *  - 认证收敛到网关: 所有经网关的请求先校验 JWT 签名/过期, 再查 Redis 黑名单
 *  - 白名单无需鉴权: /user/login(登录)、/user/logout(退出)、/product/**(商品浏览公开)
 *  - 退出登录: user-service 把 token 写入 Redis 黑名单, 网关查到即拒, 实现即时失效
 *  - 鉴权通过后把 userId/username 透传(X-User-Id/X-User-Name)给下游
 */
@Component
public class GlobalAuthFilter implements GlobalFilter, Ordered {

    /** JWT 黑名单 key 前缀(与 user-service 写入时保持一致) */
    private static final String TOKEN_BLACKLIST_PREFIX = "auth:token:blacklist:";

    private static final List<String> WHITE_LIST = List.of("/user/login", "/user/logout");
    // 公开浏览前缀: 商品浏览 + 秒杀(扣减/确认虽公开, 但单靠 Redis 幂等+DB条件更新防超卖)
    // 追加:
    //  - /user/register      注册(未登录时使用)
    //  - /user/merchant      公开查看商家信息
    //  - /user/sms/send      发送短信验证码(忘记密码流程, 未登录时使用, 必须放行)
    //  - /user/password/reset 忘记密码重置(未登录时使用, 必须放行)
    //  - /upload/files       上传图片的静态资源(商品图公开浏览)。
    //    注意: /upload/image(POST 上传)不在白名单, 不匹配此前缀, 自动走 JWT 鉴权(需登录)
    private static final List<String> PUBLIC_PREFIXES = List.of("/product", "/seckill",
            "/user/register", "/user/merchant", "/user/sms/send", "/user/password/reset",
            "/upload/files");
    /** 需要商家角色的路径前缀(网关层做第一道角色校验, 业务侧 Service 再校验一次双保险):
     *  - /merchant/       商家商品管理(product-service)
     *  - /order/merchant  商家卖出订单/发货/统计(order-service) */
    private static final List<String> MERCHANT_PREFIXES = List.of("/merchant/", "/order/merchant");

    private final ReactiveStringRedisTemplate redisTemplate;

    public GlobalAuthFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    //
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();

        // CORS 预检请求直接放行(由网关 globalcors 处理)
        if ("OPTIONS".equalsIgnoreCase(request.getMethod().name())) {
            return chain.filter(exchange);
        }
        // 白名单直接放行
        if (isWhiteList(path)) {
            return chain.filter(exchange);
        }

        // 1. 提取 Bearer JWT 并校验签名/过期
        String token = extractToken(request);
        Claims claims = token == null ? null : JwtUtil.parseToken(token);
        if (claims == null) {
            return unauthorized(exchange, "未登录或登录已过期");
        }

        // 2. 查 Redis 黑名单: 退出登录后的 token 立即失效
        return redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + token)
                .flatMap(blocked -> {
                    if (Boolean.TRUE.equals(blocked)) {
                        return unauthorized(exchange, "登录已失效，请重新登录");
                    }
                    // 3. 角色校验: 商家专属接口(/merchant/**、/order/merchant/**)要求 JWT 中 role=merchant
                    if (isMerchantPath(path)
                            && !"merchant".equals(claims.get("role"))) {
                        return forbidden(exchange, "无商家权限");
                    }
                    // 4. 鉴权通过: 把已签名的用户身份透传下游(userId/username/role)
                    ServerHttpRequest mutated = request.mutate()
                            .header("X-User-Id", claims.getSubject())
                            .header("X-User-Name", String.valueOf(claims.get("username")))
                            .header("X-User-Role", String.valueOf(claims.get("role")))
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                });
    }

    /** 白名单: 登录/退出 + 商品/秒杀浏览(公开) */
    private boolean isWhiteList(String path) {
        for (String w : WHITE_LIST) {
            if (path.equals(w) || path.startsWith(w + "/")) {
                return true;
            }
        }
        for (String p : PUBLIC_PREFIXES) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 是否商家专属路径: 命中任一商家前缀即要求 role=merchant */
    private boolean isMerchantPath(String path) {
        for (String p : MERCHANT_PREFIXES) {
            if (path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 从 Authorization 头提取 Bearer token */
    private String extractToken(ServerHttpRequest request) {
        List<String> auths = request.getHeaders().get("Authorization");
        if (auths == null || auths.isEmpty()) {
            return null;
        }
        String auth = auths.get(0);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        return auth.substring(7);
    }

    /** 返回 401 统一 JSON 结构(与后端 Result 对齐) */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        return reject(exchange, 401, message);
    }

    /** 返回 403 统一 JSON 结构(角色/权限不足, 与后端 Result 对齐) */
    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        return reject(exchange, 403, message);
    }

    /** 按 HTTP 状态码返回统一 JSON 结构(与后端 Result 对齐) */
    private Mono<Void> reject(ServerWebExchange exchange, int status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setRawStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 尽早执行, 保证在路由转发前完成鉴权
        return -100;
    }
}