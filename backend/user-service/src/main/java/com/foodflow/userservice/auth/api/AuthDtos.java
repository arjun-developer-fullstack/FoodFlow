package com.foodflow.userservice.auth.api;

import com.foodflow.userservice.auth.application.AuthCommands.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request/response bodies of the authentication API. */
public final class AuthDtos {

    private AuthDtos() {
    }

    @Schema(description = "New account")
    public record RegisterRequest(
            @Schema(example = "jane.doe@example.com")
            @NotBlank @Email @Size(max = 254) String email,

            @Schema(description = "12 to 128 characters. Passphrases are encouraged; no composition rules (NIST SP 800-63B).",
                    example = "correct horse battery staple", format = "password")
            @NotBlank @Size(min = 12, max = 128) String password,

            @Schema(example = "Jane Doe")
            @NotBlank @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must contain a non-space character")
            String fullName,

            @Schema(description = "Defaults to CUSTOMER. ADMIN cannot be self-assigned.", nullable = true)
            AccountType accountType) {

        public AccountType accountTypeOrDefault() {
            return accountType == null ? AccountType.CUSTOMER : accountType;
        }

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", accountType=" + accountType + "]";
        }
    }

    public record LoginRequest(
            @Schema(example = "jane.doe@example.com")
            @NotBlank @Email @Size(max = 254) String email,

            @Schema(format = "password")
            @NotBlank @Size(max = 128) String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + "]";
        }
    }

    @Schema(description = "Access token. The rotating refresh token is set as an HttpOnly cookie, never in the body.")
    public record TokenResponse(
            @Schema(description = "RS256-signed JWT for the Authorization header") String accessToken,
            @Schema(example = "Bearer") String tokenType,
            @Schema(description = "Seconds until the access token expires", example = "900") long expiresIn,
            Instant expiresAt) {
    }
}
