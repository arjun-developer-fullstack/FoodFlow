package com.foodflow.userservice.user.api;

import com.foodflow.platform.web.PageResponse;
import com.foodflow.userservice.common.web.CurrentUser;
import com.foodflow.userservice.user.api.UserDtos.AdminUserResponse;
import com.foodflow.userservice.user.api.UserDtos.ChangeStatusRequest;
import com.foodflow.userservice.user.application.AdminUserService;
import com.foodflow.userservice.user.domain.UserStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Requires ROLE_ADMIN: enforced by the URL rule in SecurityConfiguration and again here (defense in depth). */
@Tag(name = "Admin: Users", description = "User administration (role ADMIN)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUsers;

    public AdminUserController(AdminUserService adminUsers) {
        this.adminUsers = adminUsers;
    }

    @Operation(summary = "Search users", description = "Newest first")
    @ApiResponse(responseCode = "200", description = "Page of users")
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public PageResponse<AdminUserResponse> search(
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) @Size(max = 254) String emailPrefix,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(adminUsers.search(status, emailPrefix, page, size), AdminUserResponse::from);
    }

    @Operation(summary = "Activate or suspend a user", description = "Suspension revokes all of the user's sessions")
    @ApiResponse(responseCode = "200", description = "Updated user")
    @ApiResponse(responseCode = "404", description = "Unknown user",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Admins cannot change their own status (code CANNOT_CHANGE_OWN_STATUS)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PatchMapping("/{userId}/status")
    public AdminUserResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
                                          @Valid @RequestBody ChangeStatusRequest request) {
        return AdminUserResponse.from(adminUsers.changeStatus(CurrentUser.id(jwt), userId, request.status()));
    }
}
