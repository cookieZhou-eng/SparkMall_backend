package com.example.ai.config;

import com.example.ai.exception.BusinessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 资源文本加载器: 统一读取 classpath: 与文件系统路径下的文本资源。
 * 用于加载系统提示词 / FAQ 语料 / 工具定义, 支持用本地文件覆盖默认语料。
 */
@Component
public class ResourceTextLoader {

    /**
     * 读取文本资源(UTF-8)
     * @param path 以 classpath: 开头走类路径, 否则按文件系统路径
     */
    public String load(String path) {
        if (path == null || path.isBlank()) {
            throw new BusinessException(500, "资源路径未配置");
        }
        Resource resource = path.startsWith("classpath:")
                ? new ClassPathResource(path.substring("classpath:".length()))
                : new FileSystemResource(path);
        if (!resource.exists()) {
            throw new BusinessException(500, "资源不存在: " + path);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(500, "资源读取失败: " + path);
        }
    }
}
