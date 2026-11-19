package labreview.api;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
class ApiErrors {
    record ErrorBody(String code, String message, String field,
                     @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                     Object details) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> applicationError(ApiException error) {
        return ResponseEntity.status(error.status)
                .body(new ErrorBody(error.code, error.getMessage(), error.field, error.details));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ErrorBody> transientDatabaseConflict() {
        return applicationError(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARY_LOCK_CONFLICT",
                "数据库暂时繁忙，请重试", null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalidField(MethodArgumentNotValidException error) {
        var fieldError = error.getBindingResult().getFieldError();
        return applicationError(fieldError == null ? ApiException.invalid("请检查提交内容", null)
                : ApiException.invalid(fieldError.getDefaultMessage(), fieldError.getField()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> malformedBody() {
        return applicationError(ApiException.invalid("请检查请求格式，数量使用整数，日期使用 YYYY-MM-DD", null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> malformedParameter(MethodArgumentTypeMismatchException error) {
        return applicationError(ApiException.invalid("查询参数无效", error.getName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorBody> missingParameter(MissingServletRequestParameterException error) {
        return applicationError(ApiException.invalid("缺少请求参数", error.getParameterName()));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    ResponseEntity<ErrorBody> malformedUpload() {
        return applicationError(ApiException.invalid("请选择一份文件上传", "file"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> oversizedUpload() {
        return applicationError(new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                "文件超过允许的上传大小", "file"));
    }
}
