package com.foodflow.platform.security;

import com.foodflow.platform.error.ProblemDetails;
import com.foodflow.platform.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 401/403 responses from the security filter chain, rendered as RFC 9457 problem details.
 * The bearer-token delegates set the standard {@code WWW-Authenticate} header (RFC 6750) first.
 * Error descriptions are deliberately generic: token-validation details are never echoed back.
 */
public class ProblemDetailSecurityHandlers {

    private final ProblemDetails problems;
    private final JsonMapper jsonMapper;
    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();

    public ProblemDetailSecurityHandlers(ProblemDetails problems, JsonMapper jsonMapper) {
        this.problems = problems;
        this.jsonMapper = jsonMapper;
    }

    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> {
            bearerEntryPoint.commence(request, response, ex);
            write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", describe(ex), request);
        };
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> {
            bearerAccessDenied.handle(request, response, ex);
            write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                    "You do not have permission to perform this action.", request);
        };
    }

    private static String describe(AuthenticationException ex) {
        return ex instanceof InvalidBearerTokenException
                ?"The access token is invalid or has expired."
                : "Authentication is required to access this resource.";
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String detail,
                       HttpServletRequest request) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problems.typeFor(code).toString());
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put(ProblemDetails.CODE, code);
        String correlationId = CorrelationId.current();
        if (correlationId != null) {
            body.put(ProblemDetails.CORRELATION_ID, correlationId);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
