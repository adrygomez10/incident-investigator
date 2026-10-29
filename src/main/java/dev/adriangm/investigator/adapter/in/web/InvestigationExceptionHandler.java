package dev.adriangm.investigator.adapter.in.web;

import java.time.DateTimeException;
import java.util.NoSuchElementException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates expected application failures to stable HTTP error responses. */
@RestControllerAdvice(assignableTypes = InvestigationController.class)
public final class InvestigationExceptionHandler {
    /** HTTP error code without internal exception details or credentials. */
    public record ErrorResponse(String error) {
    }

    /** Reports an absent investigation without exposing storage details. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> missing()
    {
        return ResponseEntity.status(404).body(new ErrorResponse("NOT_FOUND"));
    }

    /** Reports invalid request input with a stable public error code. */
    @ExceptionHandler(
        {IllegalArgumentException.class, DateTimeException.class, ClassCastException.class,
            HttpMessageNotReadableException.class}
    )
    public ResponseEntity<ErrorResponse> invalid()
    {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST"));
    }

    /** Reports stale versions or conflicting state transitions. */
    @ExceptionHandler({IllegalStateException.class, DataIntegrityViolationException.class})
    public ResponseEntity<ErrorResponse> conflict()
    {
        return ResponseEntity.status(409).body(new ErrorResponse("STATE_CONFLICT"));
    }
}
