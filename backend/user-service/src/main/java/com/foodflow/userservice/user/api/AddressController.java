package com.foodflow.userservice.user.api;

import com.foodflow.userservice.common.web.CurrentUser;
import com.foodflow.userservice.user.api.UserDtos.AddressRequest;
import com.foodflow.userservice.user.api.UserDtos.AddressResponse;
import com.foodflow.userservice.user.application.AddressService;
import com.foodflow.userservice.user.domain.Address;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Tag(name = "Addresses", description = "The signed-in user's delivery addresses")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/users/me/addresses")
public class AddressController {

    private final AddressService addresses;

    public AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    @Operation(summary = "List my addresses", description = "Default address first, then oldest first")
    @GetMapping
    public List<AddressResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return addresses.list(CurrentUser.id(jwt)).stream().map(AddressResponse::from).toList();
    }

    @Operation(summary = "Add an address", description = "The first address automatically becomes the default")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "Invalid input",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Address limit reached (code ADDRESS_LIMIT_EXCEEDED)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<AddressResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody AddressRequest request) {
        Address address = addresses.create(CurrentUser.id(jwt), request.toDetails(), request.makeDefaultOrFalse());
        return ResponseEntity.created(URI.create("/api/v1/users/me/addresses/" + address.getId()))
                .body(AddressResponse.from(address));
    }

    @Operation(summary = "Replace an address")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "404", description = "No such address for this user",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/{addressId}")
    public AddressResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID addressId,
                                  @Valid @RequestBody AddressRequest request) {
        return AddressResponse.from(addresses.update(CurrentUser.id(jwt), addressId, request.toDetails(),
                request.makeDefaultOrFalse()));
    }

    @Operation(summary = "Delete an address", description = "If it was the default, the oldest remaining one becomes default")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such address for this user",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID addressId) {
        addresses.delete(CurrentUser.id(jwt), addressId);
        return ResponseEntity.noContent().build();
    }
}
