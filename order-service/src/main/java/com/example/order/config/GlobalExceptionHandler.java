package com.example.order.config;

import com.example.common.result.Result;
import com.example.order.exception.BusinessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理(统一返回 Result, 避免 Spring 默认的 Whitelabel 错误页)
 * 业务异常(BusinessException)按业务码返回; 其他运行时异常统一转成
 * Result.fail(500, message), 前端就能拿到友好的错误提示
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常: 按抛出的业务码(code)返回, 前端据此区分 400/403 等场景 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        return Result.fail(500, e.getMessage() == null ? "服务器内部错误" : e.getMessage());
    }
}
