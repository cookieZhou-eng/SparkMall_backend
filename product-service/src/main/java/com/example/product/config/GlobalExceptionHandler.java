package com.example.product.config;

import com.example.common.result.Result;
import com.example.product.exception.BusinessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理(统一返回 Result, 与 user-service 保持一致)。
 * 业务异常(Busin essException)按业务码返回; 其他异常统一转成 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常: 按抛出的业务码(code)返回, 前端据此区分 400/403 等场景 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 兜底: 未识别的运行时异常统一 500, 避免 Spring 默认 Whitelabel 错误页 */
    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        return Result.fail(500, e.getMessage() == null ? "服务器内部错误" : e.getMessage());
    }
}
