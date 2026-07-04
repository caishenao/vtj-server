package cn.cai.vtjserver.exception;

import cn.cai.vtjserver.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * Global exception translation for all controllers (AGENTS.md §7).
 *
 * <p>Design decision: business and validation failures are returned as HTTP 200 carrying a
 * {@code success:false} envelope, not as HTTP 4xx/5xx. The VTJ designer frontend inspects the
 * envelope's {@code success}/{@code code} fields to decide success, and an existing dispatch
 * path already returns {@link ApiResponse#fail} at HTTP 200 for unknown types. Diverging to a
 * raw HTTP error here would break that established wire contract. Genuine server faults are
 * still logged at ERROR with the full stack trace so operators keep full visibility.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Domain-level failures raised deliberately by the service layer. */
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Object> handleBusiness(BusinessException ex, HttpServletRequest request) {
        // These are expected control-flow signals, not defects: log at WARN without a stack trace.
        log.warn("Business error at {}: {}", request.getRequestURI(), ex.getMessage());
        return ApiResponse.fail(ex.getCode(), ex.getMessage(), null);
    }

    /** {@code @Valid} body validation failures on request DTOs. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Object> handleBodyValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("Request validation failed: {}", message);
        return ApiResponse.fail(message.isBlank() ? "Validation failed" : message, null);
    }

    /** Path/param level validation failures ({@code @Validated} on controllers). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Object> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMessage());
        return ApiResponse.fail(ex.getMessage(), null);
    }

    /** Uploaded file exceeds the configured multipart limit. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected, size limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail("Uploaded file is too large", null));
    }

    /** Last-resort handler: unexpected faults are logged with full context and masked from the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("Internal server error", null));
    }

    private static String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
