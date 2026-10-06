package com.dmc.backend.rescue;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes=RescueController.class)
public class RescueErrorHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException error) {
        return response(error.getStatusCode(), "RESCUE_REQUEST_FAILED", error.getReason());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of(
                "code", "INVALID_REQUEST", "message", "Check the supplied fields.",
                "fieldErrors", error.getBindingResult().getFieldErrors().stream().map(field -> Map.of(
                        "field", field.getField(), "message", String.valueOf(field.getDefaultMessage()))).toList()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed() { return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Provide valid fields."); }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<?> conflict() { return response(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Record changed. Reload before updating."); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable() { return response(HttpStatus.SERVICE_UNAVAILABLE, "RESCUE_UNAVAILABLE", "Rescue records are temporarily unavailable."); }
    private ResponseEntity<?> response(HttpStatusCode status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code, "message", message==null ? "Request failed." : message));
    }
}
