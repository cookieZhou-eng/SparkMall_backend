package com.example.ai.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 请求头透传配置。
 *
 * 网关鉴权通过后会把解析出的用户身份放入请求头(X-User-Id / X-User-Name / X-User-Role),
 * ai-service 调用 order-service / product-service 时需要把这些请求头原样带过去,
 * 否则下游接口(如 /refund/**)拿不到 X-User-Id 会按"未登录"处理。
 */
@Configuration
public class FeignUserHeaderConfig {

    /** 需要透传给下游服务的用户身份请求头 */
    private static final String[] USER_HEADERS = {"X-User-Id", "X-User-Name", "X-User-Role"};

    @Bean
    public RequestInterceptor userHeaderInterceptor() {
        return (RequestTemplate template) -> {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return; // 非 HTTP 请求上下文(如启动期/定时任务), 不透传
            }
            HttpServletRequest request = attrs.getRequest();
            for (String header : USER_HEADERS) {
                String value = request.getHeader(header);
                if (value != null && !value.isBlank()) {
                    template.header(header, value);
                }
            }
        };
    }
}
