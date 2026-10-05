package com.dmc.backend.reporting;

import com.dmc.backend.auth.AuthException;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice(assignableTypes = {GroundReportController.class, OfficerReviewController.class, ReportSyncController.class, VerifiedEvidenceController.class})
public class ReportErrorHandler {
    public record ErrorResponse(String code, String message, List<ReportException.FieldError> fieldErrors) { }
    @ExceptionHandler(ReportException.class)
    ResponseEntity<ErrorResponse> report(ReportException exception) {
        return error(exception.status(), exception.code(), exception.getMessage(), exception.fields());
    }
    @ExceptionHandler(AuthException.class)
    ResponseEntity<ErrorResponse> auth(AuthException exception) {
        return error(exception.status(), exception.code(), exception.getMessage(), List.of());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(field -> new ReportException.FieldError(field.getField(), "invalid", field.getDefaultMessage())).toList();
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Check the supplied fields.", fields);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, MissingServletRequestPartException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> malformed(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Provide valid fields and the required version.", List.of());
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> oversized() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "PHOTO_TOO_LARGE", "Photo must not exceed 5 MiB.", List.of());
    }
    @ExceptionHandler({OptimisticLockingFailureException.class, DuplicateKeyException.class})
    ResponseEntity<ErrorResponse> conflict() {
        return error(HttpStatus.CONFLICT, "REPORT_VERSION_CONFLICT", "The report changed. Reload it before trying again.", List.of());
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "REPORTS_UNAVAILABLE", "Reports are temporarily unavailable.", List.of());
    }
    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
            List<ReportException.FieldError> fields) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ErrorResponse(code, message, fields));
    }
}
