package com.example.common.result;

import lombok.Data;

import java.util.List;

// Result类用于封装接口的返回结果, 包含状态码、消息和数据
// 前端可以根据 code 判断请求是否成功, 然后取 data 渲染页面
@Data
public class Result<T> {
    private Integer code;     // 状态码: 200-成功 401-未登录 403-无权限 500-服务异常
    private String message;   // 提示消息
    private T data;           // 业务数据

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.code = 200;
        result.message = "success";
        result.data = data;
        return result;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> fail(String message) {
        Result<T> result = new Result<>();
        result.code = 500;
        result.message = message;
        return result;
    }

    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> result = new Result<>();
        result.code = code;
        result.message = message;
        return result;
        }

    /**
     * 分页结果封装(对应前端 el-pagination 组件的数据结构)
     */
    @Data
    public static class PageData<T> {
        private Long total;        // 总记录数
        private List<T> records;  // 当前页数据

        public PageData(Long total, List<T> records) {
            this.total = total;
            this.records = records;
        }
    }
}
