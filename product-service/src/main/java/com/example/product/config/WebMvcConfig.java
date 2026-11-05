package com.example.product.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置: 把 /upload/files/** 映射到本地磁盘上传目录,
 * 使上传的图片可以通过 product-service(经网关) 直接 GET 公开访问。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // /upload/files/2026/11/xx.jpg → file:{uploadDir}/2026/11/xx.jpg
        // Windows 路径反斜杠统一成正斜杠, 保证 file: URL 可解析
        registry.addResourceHandler("/upload/files/**")
                .addResourceLocations("file:" + uploadDir.replace("\\", "/") + "/");
    }
}
