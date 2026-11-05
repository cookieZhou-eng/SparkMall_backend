package com.example.product.controller;

import com.example.common.result.Result;
import com.example.product.service.UploadService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片上传控制器(薄控制器): 网关 /upload/** 路由转发过来。
 *
 * 分层说明:
 *  - 只做参数接收与 Result 封装, 校验/存储逻辑在 UploadService。
 *  - /upload/image 是写操作, 不在网关公开白名单, GlobalAuthFilter 会做 JWT 鉴权
 *    并透传 X-User-Id, 未登录直接 401(参数声明 Long userId 即强制要求登录)。
 */
@RestController
@RequestMapping("/upload")
public class UploadController {

    @Resource
    private UploadService uploadService;

    /**
     * 上传图片: 返回可公开访问的相对 URL(如 /upload/files/2026/11/xx.jpg), 前端拼网关地址
     */
    @PostMapping("/image")
    public Result<String> uploadImage(@RequestHeader("X-User-Id") Long userId,
                                      @RequestParam("file") MultipartFile file) {
        return Result.success(uploadService.uploadImage(file));
    }
}
