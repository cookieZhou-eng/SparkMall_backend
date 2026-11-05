package com.example.product.service;

import com.example.product.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/**
 * 图片上传服务: 校验 → 保存到本地 upload 目录 → 返回可公开访问的相对 URL。
 *
 * 存储规则: app.upload-dir(默认 ${user.dir}/upload) 下按 yyyy/MM 分目录,
 * 文件名 UUID + 原扩展名, 避免 重名/路径穿越/中文文件名 问题。
 * 返回 "/upload/files/2026/11/xx.jpg" 相对路径, 前端自行拼接网关地址。
 */
@Service
public class UploadService {

    /** 允许上传的图片扩展名(小写) */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    /** 单文件大小上限: 5MB(与 spring.servlet.multipart.max-file-size 一致) */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024L;

    @Value("${app.upload-dir}")
    private String uploadDir;

    /**
     * 上传图片: 非空/扩展名/大小校验 → 保存到 {uploadDir}/yyyy/MM/uuid.ext → 返回相对 URL。
     */
    public String uploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的图片");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(400, "图片大小不能超过 5MB");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.lastIndexOf('.') < 0) {
            throw new BusinessException(400, "仅支持 jpg/jpeg/png/gif/webp 格式图片");
        }
        String ext = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(400, "仅支持 jpg/jpeg/png/gif/webp 格式图片");
        }

        // 按 yyyy/MM 分子目录存放, 文件名用 UUID 防重名
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        Path dir = Paths.get(uploadDir, dateDir);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID().toString().replace("-", "") + "." + ext);
            file.transferTo(target.toFile());
            return "/upload/files/" + dateDir + "/" + target.getFileName();
        } catch (Exception e) {
            throw new BusinessException(500, "图片保存失败, 请稍后重试");
        }
    }
}
