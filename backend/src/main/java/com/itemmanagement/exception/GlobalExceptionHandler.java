package com.itemmanagement.exception;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        Map<String, Object> body = base(ex.getStatus(), ex.getMessage());
        if (!ex.getFieldErrors().isEmpty()) {
            body.put("fieldErrors", ex.getFieldErrors());
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        Map<String, Object> body = base(HttpStatus.BAD_REQUEST, "Please correct the highlighted fields");
        body.put("fieldErrors", fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException ex) {
        logIntegrityViolation(ex);
        return ResponseEntity.badRequest().body(base(HttpStatus.BAD_REQUEST,
                "This change conflicts with existing data. Please check codes, names or connected records."));
    }

    /**
     * The database message can quote the rejected values (usernames, emails), so
     * only the exception type, SQLState and constraint name are logged. Those
     * identify the violated rule without copying personal data into log files.
     */
    private void logIntegrityViolation(DataIntegrityViolationException ex) {
        String sqlState = null;
        String constraint = null;
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (sqlState == null && cause instanceof SQLException sqlException) {
                sqlState = sqlException.getSQLState();
            }
            if (constraint == null && cause instanceof ConstraintViolationException violation) {
                constraint = violation.getConstraintName();
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        log.warn("Data integrity violation: type={}, sqlState={}, constraint={}",
                ex.getMostSpecificCause().getClass().getSimpleName(), sqlState, constraint);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest().body(base(HttpStatus.BAD_REQUEST,
                "The uploaded file is larger than the allowed size (10 MB)"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException ex) {
        return ResponseEntity.badRequest().body(base(HttpStatus.BAD_REQUEST,
                "Select an Excel file to upload"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException ex) {
        return ResponseEntity.badRequest().body(base(HttpStatus.BAD_REQUEST,
                "The upload could not be read. Select a valid Excel file"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(base(HttpStatus.FORBIDDEN, "You do not have permission to perform this action"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(base(HttpStatus.UNAUTHORIZED, "Authentication required"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(base(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again."));
    }

    private Map<String, Object> base(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
