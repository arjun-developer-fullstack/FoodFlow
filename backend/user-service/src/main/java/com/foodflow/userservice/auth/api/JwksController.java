package com.foodflow.userservice.auth.api;

import com.foodflow.userservice.auth.application.PublicKeySetProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Public JWK Set (RFC 7517) used by the API gateway and every service to verify access tokens.
 * Contains public keys only.
 */
@Tag(name = "Keys", description = "Token verification keys")
@RestController
public class JwksController {

    static final String JWK_SET_MEDIA_TYPE = "application/jwk-set+json";

    private final PublicKeySetProvider keys;

    public JwksController(PublicKeySetProvider keys) {
        this.keys = keys;
    }

    @Operation(summary = "Public signing keys (JWKS)")
    @GetMapping(path = "/.well-known/jwks.json", produces = {JWK_SET_MEDIA_TYPE, "application/json"})
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(keys.publicJwkSet());
    }
}
