package labreview.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    final HttpStatus status;
    final String code;
    final String field;
    final Object details;

    public ApiException(HttpStatus status, String code, String message, String field) {
        this(status, code, message, field, null);
    }

    public ApiException(HttpStatus status, String code, String message, String field, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
        this.details = details;
    }

    public static ApiException invalid(String message, String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message, field);
    }

    public static ApiException missing(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, null);
    }
}
