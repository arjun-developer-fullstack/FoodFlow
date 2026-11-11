package com.foodflow.userservice.user.api;

import com.foodflow.userservice.user.domain.Address;
import com.foodflow.userservice.user.domain.AddressDetails;
import com.foodflow.userservice.user.domain.Role;
import com.foodflow.userservice.user.domain.User;
import com.foodflow.userservice.user.domain.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response bodies of the profile, address and admin APIs. */
public final class UserDtos {

    /** E.164: '+', country code, subscriber number; 8 to 15 digits in total. */
    static final String E164 = "^\\+[1-9]\\d{6,14}$";

    private UserDtos() {
    }

    // ------------------------------------------------------------------ profile

    public record UserProfileResponse(UUID id, String email, String fullName, String phone, List<Role> roles,
                                      boolean emailVerified, Instant createdAt) {

        static UserProfileResponse from(User user) {
            return new UserProfileResponse(user.getId(), user.getEmail(), user.getFullName(), user.getPhone(),
                    sortedRoles(user), user.isEmailVerified(), user.getCreatedAt());
        }
    }

    @Schema(description = "Partial update: omitted (null) fields stay unchanged; an empty phone removes it")
    public record UpdateProfileRequest(
            @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "must contain a non-space character")
            String fullName,

            @Schema(example = "+919876543210")
            @Pattern(regexp = E164 + "|^$", message = "must be an E.164 phone number, e.g. +919876543210")
            String phone) {
    }

    // ------------------------------------------------------------------ addresses

    public record AddressRequest(
            @Schema(example = "Home") @NotBlank @Size(max = 50) String label,
            @Schema(example = "221B Baker Street") @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @Schema(example = "London") @NotBlank @Size(max = 100) String city,
            @Schema(example = "NW1 6XE") @NotBlank @Size(max = 20) String postalCode,
            @Schema(description = "ISO 3166-1 alpha-2", example = "GB")
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be an ISO 3166-1 alpha-2 code")
            String country,
            @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            @Schema(description = "Make this the default delivery address", defaultValue = "false")
            Boolean makeDefault) {

        AddressDetails toDetails() {
            return new AddressDetails(label, line1, line2, city, postalCode, country, latitude, longitude);
        }

        boolean makeDefaultOrFalse() {
            return Boolean.TRUE.equals(makeDefault);
        }
    }

    public record AddressResponse(UUID id, String label, String line1, String line2, String city, String postalCode,
                                  String country, Double latitude, Double longitude, boolean isDefault,
                                  Instant createdAt, Instant updatedAt) {

        static AddressResponse from(Address address) {
            return new AddressResponse(address.getId(), address.getLabel(), address.getLine1(), address.getLine2(),
                    address.getCity(), address.getPostalCode(), address.getCountry(), address.getLatitude(),
                    address.getLongitude(), address.isDefaultAddress(), address.getCreatedAt(),
                    address.getUpdatedAt());
        }
    }

    // ------------------------------------------------------------------ admin

    public record AdminUserResponse(UUID id, String email, String fullName, List<Role> roles, UserStatus status,
                                    boolean emailVerified, Instant lockedUntil, Instant lastLoginAt,
                                    Instant createdAt) {

        static AdminUserResponse from(User user) {
            return new AdminUserResponse(user.getId(), user.getEmail(), user.getFullName(), sortedRoles(user),
                    user.getStatus(), user.isEmailVerified(), user.getLockedUntil(), user.getLastLoginAt(),
                    user.getCreatedAt());
        }
    }

    public record ChangeStatusRequest(@NotNull UserStatus status) {
    }

    private static List<Role> sortedRoles(User user) {
        return user.getRoles().stream().sorted().toList();
    }
}
