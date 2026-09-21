package com.rwa.common;

public record Result<T>(boolean success, String message, T data) {
    public static <T> Result<T> ok(T data) {
        return new Result<>(true, "success", data);
    }
    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(true, message, data);
    }
    public static <T> Result<T> fail(String message) {
        return new Result<>(false, message, null);
    }
}
