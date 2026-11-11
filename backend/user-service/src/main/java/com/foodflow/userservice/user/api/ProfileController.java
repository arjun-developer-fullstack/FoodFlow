package com.foodflow.userservice.user.api;

import com.foodflow.userservice.common.web.CurrentUser;
import com.foodflow.userservice.user.api.UserDtos.UpdateProfileRequest;
import com.foodflow.userservice.user.api.UserDtos.UserProfileResponse;
import com.foodflow.userservice.user.application.UpdateProfileCommand;
import com.foodflow.userservice.user.application.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Profile", description = "The signed-in user's own profile")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/users/me")
public class ProfileController {

    private final UserProfileService profiles;

    public ProfileController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    @Operation(summary = "Get my profile")
    @ApiResponse(responseCode = "200", description = "Profile")
    @ApiResponse(responseCode = "401", description = "Missing or invalid access token",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public UserProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserProfileResponse.from(profiles.getProfile(CurrentUser.id(jwt)));
    }

    @Operation(summary = "Update my profile")
    @ApiResponse(responseCode = "200", description = "Updated profile")
    @ApiResponse(responseCode = "400", description = "Invalid input",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Phone used by another account (code PHONE_ALREADY_IN_USE)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PatchMapping
    public UserProfileResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        return UserProfileResponse.from(profiles.updateProfile(CurrentUser.id(jwt),
                new UpdateProfileCommand(request.fullName(), request.phone())));
    }
}
