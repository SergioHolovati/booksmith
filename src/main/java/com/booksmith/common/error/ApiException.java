package com.booksmith.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Erro de negócio convertido em resposta RFC 9457 (application/problem+json)
 * pelo {@link GlobalExceptionHandler}. O {@code code} é estável e pode ser usado pelo frontend.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.code = code;
        this.properties = properties;
    }

    /**
     * Guarda a exceção original como causa: aparece no log (para achar onde o erro começou), nunca na resposta ao
     * cliente, que só vê o {@code code} e a mensagem.
     */
    public ApiException causedBy(Throwable cause) {
        initCause(cause);
        return this;
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }

    public static ApiException conflict(String code, String message, Map<String, Object> properties) {
        return new ApiException(HttpStatus.CONFLICT, code, message, properties);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
