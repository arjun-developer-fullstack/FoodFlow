package com.foodflow.platform.error;

import com.foodflow.platform.web.CorrelationId;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.Locale;

/**
 * Builds RFC 9457 problem details with FoodFlow's extension members:
 * {@code code} (stable machine-readable error code) and {@code correlationId}.
 */
public final class ProblemDetails {

    public static final String CODE = "code";
    public static final String CORRELATION_ID = "correlationId";
    public static final String ERRORS = "errors";

    private final String typeBaseUri;

    public ProblemDetails(String typeBaseUri) {
        this.typeBaseUri = typeBaseUri.endsWith("/") ? typeBaseUri : typeBaseUri + "/";
    }

    public ProblemDetail create(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(typeFor(code));
        HttpStatus resolved = HttpStatus.resolve(status.value());
        if (resolved != null) {
            problem.setTitle(resolved.getReasonPhrase());
        }
        problem.setProperty(CODE, code);
        enrich(problem);
        return problem;
    }

    /** Adds the correlation id (if any) to a problem created elsewhere, e.g. by Spring MVC. */
    public ProblemDetail enrich(ProblemDetail problem) {
        String correlationId = CorrelationId.current();
        if (correlationId != null) {
            problem.setProperty(CORRELATION_ID, correlationId);
        }
        return problem;
    }

    public URI typeFor(String code) {
        return URI.create(typeBaseUri + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    public static HttpStatus statusFor(ErrorCategory category) {
        return switch (category) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BUSINESS_RULE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case LOCKED -> HttpStatus.LOCKED;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
