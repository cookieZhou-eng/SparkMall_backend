package com.example.product.exception;

/**
 * 业务异常: 携带业务码(code)与提示信息, 由全局异常处理器转成 Result.fail(code, message)。
 * 业务码与 Result 的 code 语义一致: 400 参数不合法 / 401 未登录 / 403 无权限 / 500 服务异常。
 */
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
