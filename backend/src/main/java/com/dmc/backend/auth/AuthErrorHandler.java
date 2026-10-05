package com.dmc.backend.auth;

import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthErrorHandler {
    public record FieldError(String field, String message) { }
    public record ErrorResponse(String code, String message, List<FieldError> fieldErrors) { }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<ErrorResponse> auth(AuthException exception) {
        return error(exception.status(), exception.code(), exception.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(field -> new FieldError(field.getField(), field.getDefaultMessage())).toList();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Check the supplied fields.", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> malformed() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Provide a valid JSON request.", List.of());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE", "Authentication is temporarily unavailable.", List.of());
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, List<FieldError> fields) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, fields));
    }
}
