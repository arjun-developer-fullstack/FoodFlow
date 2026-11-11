package com.foodflow.platform.error;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Translates every exception into an RFC 9457 {@code application/problem+json} response.
 *
 * <ul>
 *   <li>{@link DomainException}: status derived from its {@link ErrorCategory}, message exposed, plus
 *       {@code Retry-After} when the exception knows when a retry can succeed.</li>
 *   <li>Bean-validation failures: 400 with a field-level {@code errors} array.</li>
 *   <li>Spring MVC exceptions (malformed JSON, 405, 415, ...): handled by the superclass.</li>
 *   <li>Anything unexpected: 500 with a generic message. Details are logged, never returned.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemDetails problems;

    public GlobalExceptionHandler(ProblemDetails problems) {
        this.problems = problems;
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
        HttpStatus status = ProblemDetails.statusFor(ex.category());
        if (ex.category() == ErrorCategory.UNAVAILABLE) {
            log.warn("Temporarily unavailable: {}", ex.errorCode());
        } else if (status.is5xxServerError()) {
            log.error("Domain failure {}", ex.errorCode(), ex);
        } else {
            log.debug("Domain failure {}: {}", ex.errorCode(), ex.getMessage());
        }
        ProblemDetail problem = problems.create(status, ex.errorCode(), ex.getMessage());
        return ex.retryAfter()
                .map(delay -> ResponseEntity.status(problem.getStatus())
                        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(delay)))
                        .body(problem))
                .orElseGet(() -> respond(problem));
    }

    /** Whole seconds, rounded up so a client never retries too early; at least 1. */
    static long retryAfterSeconds(Duration delay) {
        long seconds = delay.getSeconds() + (delay.getNano() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return respond(validationProblem(violations));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.info("Concurrent modification detected: {}", ex.getMessage());
        return respond(problems.create(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified concurrently. Reload it and retry."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        // Usually a unique-constraint race that slipped past an application-level check. The driver
        // message is not logged: PostgreSQL includes the offending values (e.g. an e-mail address).
        log.warn("Data integrity violation ({})", ex.getMostSpecificCause().getClass().getName());
        return respond(problems.create(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "The request conflicts with existing data."));
    }

    /**
     * Method-security denials (e.g. {@code @PreAuthorize}) surface inside the dispatcher; answer
     * them here so they do not fall through to the generic 500 handler.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return respond(problems.create(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to perform this action."));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return respond(problems.create(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required to access this resource."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(problems.create(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Quote the correlationId when contacting support."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        ProblemDetail problem = validationProblem(violations);
        return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        ProblemDetail problem = validationProblem(violations);
        return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
    }

    /** Adds the FoodFlow extension members to problems produced by Spring MVC itself. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey(ProblemDetails.CODE)) {
                problem.setProperty(ProblemDetails.CODE, "REQUEST_REJECTED");
            }
            problems.enrich(problem);
        }
        return response;
    }

    private ProblemDetail validationProblem(List<FieldViolation> violations) {
        ProblemDetail problem = problems.create(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "The request contains invalid fields.");
        problem.setProperty(ProblemDetails.ERRORS, violations);
        return problem;
    }

    private static FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** One invalid input field. */
    public record FieldViolation(String field, String message) {
    }
}
