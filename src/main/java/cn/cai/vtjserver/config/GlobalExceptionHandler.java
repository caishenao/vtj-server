package cn.cai.vtjserver.config;

import cn.cai.vtjserver.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<?> handleIllegalArgument(IllegalArgumentException e) {
        return ApiResponse.fail(e.getMessage(), null);
    }

    @ExceptionHandler(IOException.class)
    public ApiResponse<?> handleIo(IOException e) {
        log.error("IO error", e);
        return ApiResponse.fail("File operation failed: " + e.getMessage(), null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<?> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ApiResponse.fail("File size exceeds the maximum limit", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneric(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("Internal server error", null));
    }
}
