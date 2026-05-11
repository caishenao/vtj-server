package cn.cai.vtjserver.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ApiResponse<T> {
    private int code;
    private String msg;
    private T data;
    private Object stack;
    private boolean success;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data, null, true);
    }

    public static ApiResponse<Object> fail(String msg, Object data) {
        return new ApiResponse<>(1, msg, data, null, false);
    }
}
