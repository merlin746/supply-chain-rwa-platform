package com.rwa.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> validation(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getDefaultMessage() == null ? "字段格式不正确" : e.getDefaultMessage())
                .distinct().collect(java.util.stream.Collectors.joining("；"));
        return Result.fail(message.isBlank() ? "请检查输入信息" : message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> invalidRequest(Exception error) {
        return Result.fail("请求格式不正确，请检查日期、金额及必填字段");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> invalidArgument(IllegalArgumentException error) {
        return Result.fail(error.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<?> invalidState(IllegalStateException error) {
        return Result.fail(error.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<?> duplicateOrInvalidData(DataIntegrityViolationException error) {
        return Result.fail("记录已存在或数据不完整，请刷新后核对结果");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> unexpected(Exception error) {
        log.error("业务请求处理失败", error);
        return Result.fail("服务处理失败，请稍后重试");
    }
}
