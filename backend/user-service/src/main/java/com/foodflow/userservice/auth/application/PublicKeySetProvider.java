package com.foodflow.userservice.auth.application;

import java.util.Map;

/** Outbound port: the issuer's public signing keys as a JWK Set (RFC 7517) JSON object. */
@FunctionalInterface
public interface PublicKeySetProvider {

    Map<String, Object> publicJwkSet();
}
