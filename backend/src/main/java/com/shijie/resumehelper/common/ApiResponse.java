package com.shijie.resumehelper.common;

/**
 * 统一响应包装。
 *
 * <p>产品方案 8 的接口草案里都是业务路径（{@code /api/jobs}、{@code /api/materials} ...）。
 * 为了让前端能统一处理成功/失败，这里包一层 {@code {success, message, data}}。
 * 如果队友的模块约定直接用裸对象返回，把控制器里的 {@code ApiResponse.ok(...)} 去掉即可。
 *
 * @param success 是否成功
 * @param message 失败原因或提示，成功时通常为 null
 * @param data    业务数据
 */
public record ApiResponse<T>(boolean success, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, data);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, message, data);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }
}
