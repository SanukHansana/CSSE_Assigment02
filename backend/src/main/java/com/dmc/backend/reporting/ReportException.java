package com.dmc.backend.reporting;

import java.util.List;
import org.springframework.http.HttpStatus;

/** Stable client errors; never include private storage or database details. */
public class ReportException extends RuntimeException {
    public record FieldError(String field, String code, String message) { }
    private final HttpStatus status;
    private final String code;
    private final List<FieldError> fields;

    public ReportException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }
    public ReportException(HttpStatus status, String code, String message, List<FieldError> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = List.copyOf(fields);
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<FieldError> fields() { return fields; }
}
