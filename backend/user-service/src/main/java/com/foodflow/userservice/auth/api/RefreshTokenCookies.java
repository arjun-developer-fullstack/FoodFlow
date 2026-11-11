package com.foodflow.userservice.auth.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.ResponseCookie;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The refresh token travels only in this cookie:
 * <ul>
 *   <li>{@code HttpOnly}: unreadable by JavaScript, so an XSS bug cannot exfiltrate it</li>
 *   <li>{@code Secure}: HTTPS only (configurable for plain-HTTP local development)</li>
 *   <li>{@code SameSite=Strict}: not sent on cross-site requests, which is the CSRF defense
 *       for the cookie-authenticated refresh/logout endpoints</li>
 *   <li>{@code Path=/api/v1/auth}: not sent to any other endpoint</li>
 * </ul>
 */
public class RefreshTokenCookies {

    private final Settings settings;
    private final Clock clock;

    public RefreshTokenCookies(Settings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public ResponseCookie issue(String refreshToken, Instant expiresAt) {
        Duration maxAge = Duration.between(Instant.now(clock), expiresAt);
        return base(refreshToken).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (settings.name().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public String name() {
        return settings.name();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(settings.name(), value)
                .httpOnly(true)
                .secure(settings.secure())
                .sameSite("Strict")
                .path(settings.path());
    }

    /** {@code foodflow.auth.cookie.*} */
    @ConfigurationProperties(prefix = "foodflow.auth.cookie")
    public record Settings(
            @DefaultValue("ff_refresh") String name,
            @DefaultValue("/api/v1/auth") String path,
            @DefaultValue("true") boolean secure) {
    }
}
