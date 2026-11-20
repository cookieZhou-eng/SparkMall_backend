package com.example.ai.config;

import com.example.ai.exception.BusinessException;
import com.example.common.result.Result;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理(统一返回 Result, 与 order-service 保持一致):
 * 业务异常按业务码返回; 其他运行时异常统一转成 Result.fail(500, message)。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常: 按抛出的业务码(code)返回 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        return Result.fail(500, e.getMessage() == null ? "服务器内部错误" : e.getMessage());
    }
}
