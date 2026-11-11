package com.foodflow.platform.error;

/**
 * Transport-agnostic classification of domain failures.
 * The web layer maps each category to an HTTP status; domain code never references HTTP.
 */
public enum ErrorCategory {
    /** Input is syntactically valid but semantically wrong (400). */
    VALIDATION,
    /** Caller is not (or no longer) authenticated, e.g. bad credentials or revoked token (401). */
    UNAUTHENTICATED,
    /** Caller is authenticated but not allowed to perform the action (403). */
    FORBIDDEN,
    /** Resource does not exist or is not visible to the caller (404). */
    NOT_FOUND,
    /** Request conflicts with current state, e.g. duplicate e-mail (409). */
    CONFLICT,
    /** Business rule prevents the operation (422). */
    BUSINESS_RULE,
    /** Resource is temporarily locked, e.g. account lockout (423). */
    LOCKED,
    /** Caller exceeded a rate limit (429). */
    RATE_LIMITED,
    /** Temporarily unable to serve the request, e.g. a saturated bulkhead; the client may retry (503). */
    UNAVAILABLE
}
