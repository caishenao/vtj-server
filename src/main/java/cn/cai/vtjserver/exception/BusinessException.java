package cn.cai.vtjserver.exception;

import lombok.Getter;

/**
 * Business-level exception carrying a non-zero {@code code} that maps directly onto the
 * unified {@link cn.cai.vtjserver.dto.ApiResponse} envelope.
 *
 * <p>Thrown by the service layer when a request is well-formed at the transport level but
 * invalid at the domain level (missing identifiers, unknown dispatch types, etc.). It is
 * translated into a {@code success:false} envelope by {@link GlobalExceptionHandler} rather
 * than surfacing as an HTTP 5xx, because the VTJ frontend parses the envelope, not the HTTP
 * status, to detect failures.
 */
@Getter
public class BusinessException extends RuntimeException {
    /** Business error code carried into {@code ApiResponse.code}; {@code 0} is reserved for success. */
    private final int code;

    public BusinessException(String message) {
        this(1, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
