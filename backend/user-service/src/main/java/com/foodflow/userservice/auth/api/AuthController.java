package com.foodflow.userservice.auth.api;

import com.foodflow.userservice.auth.api.AuthDtos.LoginRequest;
import com.foodflow.userservice.auth.api.AuthDtos.RegisterRequest;
import com.foodflow.userservice.auth.api.AuthDtos.TokenResponse;
import com.foodflow.userservice.auth.application.AuthCommands.AuthSession;
import com.foodflow.userservice.auth.application.AuthCommands.ClientContext;
import com.foodflow.userservice.auth.application.AuthCommands.LoginCommand;
import com.foodflow.userservice.auth.application.AuthCommands.RegisterCommand;
import com.foodflow.userservice.auth.application.AuthService;
import com.foodflow.userservice.auth.domain.AuthExceptions.InvalidRefreshTokenException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Tag(name = "Authentication", description = "Sign-up, sign-in and session management")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_COOKIE_DOC =
            "Rotating refresh token (HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth)";

    private final AuthService authService;
    private final RefreshTokenCookies cookies;
    private final Clock clock;

    public AuthController(AuthService authService, RefreshTokenCookies cookies, Clock clock) {
        this.authService = authService;
        this.cookies = cookies;
        this.clock = clock;
    }

    @Operation(summary = "Register a new account and sign in")
    @ApiResponse(responseCode = "201", description = "Account created; signed in",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = REFRESH_COOKIE_DOC))
    @ApiResponse(responseCode = "400", description = "Invalid input",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "E-mail already registered (code EMAIL_ALREADY_REGISTERED)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Password verification is saturated (code AUTHENTICATION_BUSY); retry shortly",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/register")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request,
                                                  HttpServletRequest httpRequest) {
        AuthSession session = authService.register(
                new RegisterCommand(request.email(), request.password(), request.fullName(),
                        request.accountTypeOrDefault()),
                clientContext(httpRequest));
        return sessionResponse(ResponseEntity.created(URI.create("/api/v1/users/me")), session);
    }

    @Operation(summary = "Sign in with e-mail and password")
    @ApiResponse(responseCode = "200", description = "Signed in",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = REFRESH_COOKIE_DOC))
    @ApiResponse(responseCode = "401", description = "Invalid e-mail or password (code INVALID_CREDENTIALS)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Account suspended (code ACCOUNT_SUSPENDED)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "423", description = "Temporarily locked after repeated failures (code ACCOUNT_LOCKED)",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds until the lock expires"),
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Password verification is saturated (code AUTHENTICATION_BUSY); retry shortly",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        AuthSession session = authService.login(new LoginCommand(request.email(), request.password()),
                clientContext(httpRequest));
        return sessionResponse(ResponseEntity.ok(), session);
    }

    @Operation(summary = "Exchange the refresh-token cookie for a new access token",
            description = "Single use: the cookie is rotated on every call. Replaying an old refresh token "
                    + "revokes all sessions that descend from the same sign-in.")
    @Parameter(in = ParameterIn.COOKIE, name = "ff_refresh", required = true, description = REFRESH_COOKIE_DOC)
    @ApiResponse(responseCode = "200", description = "New access token; refresh cookie rotated")
    @ApiResponse(responseCode = "401", description = "Missing, expired, revoked or reused refresh token "
            + "(code INVALID_REFRESH_TOKEN); the cookie is cleared, except for a token rotated moments ago "
            + "(a concurrent request already received its successor)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String refreshToken = cookies.read(httpRequest);
        try {
            if (refreshToken == null) {
                throw new InvalidRefreshTokenException();
            }
            AuthSession session = authService.refresh(refreshToken, clientContext(httpRequest));
            return sessionResponse(ResponseEntity.ok(), session);
        } catch (InvalidRefreshTokenException e) {
            // A benign race inside the grace window must not clear the cookie: the browser may already
            // hold the successor token from the concurrent request, and clearing it would sign the user out.
            if (!e.keepsCookie()) {
                httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
            }
            throw e;
        }
    }

    @Operation(summary = "Sign out", description = "Revokes the session family of the refresh-token cookie. Idempotent.")
    @Parameter(in = ParameterIn.COOKIE, name = "ff_refresh", description = REFRESH_COOKIE_DOC)
    @ApiResponse(responseCode = "204", description = "Signed out; cookie cleared")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        String refreshToken = cookies.read(httpRequest);
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    private ResponseEntity<TokenResponse> sessionResponse(ResponseEntity.BodyBuilder builder, AuthSession session) {
        long expiresIn = Math.max(0, Duration.between(Instant.now(clock), session.accessTokenExpiresAt()).toSeconds());
        return builder
                .header(HttpHeaders.SET_COOKIE,
                        cookies.issue(session.refreshToken(), session.refreshTokenExpiresAt()).toString())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new TokenResponse(session.accessToken(), "Bearer", expiresIn, session.accessTokenExpiresAt()));
    }

    private static ClientContext clientContext(HttpServletRequest request) {
        return new ClientContext(request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
    }
}
