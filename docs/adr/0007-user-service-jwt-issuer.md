# ADR-0007: User Service as JWT issuer (RS256 + JWKS, rotating refresh tokens)

* Status: Accepted
* Date: 2026-11-07

## Context
All services need stateless, verifiable identity. Keycloak would add SSO/MFA but also another
stateful system to operate. Current requirements are first-party login with four roles.

## Decision
* `user-service` issues **RS256 JWT access tokens** (15 min) using Spring Security's JOSE (Nimbus)
  support. No custom cryptography.
  * Claims: `sub`, `roles`, `restaurantIds`, `courierId`, `iss`, `aud`, `jti`.
* Public keys are published at `/.well-known/jwks.json` with `kid`, which allows overlapping key
  rotation. Private keys come from AWS Secrets Manager / KMS and never from the repo.
* **Refresh tokens** are opaque, stored as hashes, valid for 14 days and rotated on every use.
  * Presenting a token that was already used revokes the whole token family.
  * Web clients receive them in a `HttpOnly; Secure; SameSite=Strict` cookie.
* Passwords use Argon2id via `DelegatingPasswordEncoder`. Login is rate-limited and accounts lock
  after repeated failures.
* **Zero trust:** the Gateway validates tokens, and every service validates them again as an
  OAuth2 resource server. Access tokens are self-contained and cannot be recalled before they
  expire; see *Access-token revocation* below (a denylist is planned, not yet built).
* Endpoints follow OIDC conventions so Keycloak (or another IdP) can replace the issuer later
  without changing resource servers.

## Access-token revocation

*Added 2026-11-11 after acceptance test AT-11. Earlier text claimed a `jti` denylist already
existed; it does not.*

**Today: revoke the session, let the access token expire.**
* Access tokens are verified statelessly (signature, `iss`, `aud`, `exp`) at the Gateway and in every
  service. Nothing is looked up per request, so an issued access token stays valid until `exp`
  (at most 15 min).
* Revocation acts on the **refresh-token family** (one family = one sign-in = one session). Revoked
  families can never mint a new access token, so a session ends at most 15 min after revocation.

| Event | Refresh tokens | Access tokens already issued |
|---|---|---|
| Sign-out | Family revoked immediately; cookie cleared | Valid until `exp` (≤ 15 min) |
| Reuse of a rotated token (outside the 10 s grace window) | Whole family revoked immediately | Valid until `exp` |
| Benign race inside the grace window | Nothing revoked; the cookie is **not** cleared | Unaffected |
| Administrative suspension | All of the user's families revoked; every refresh re-checks status | Valid until `exp` |
| Temporary lockout (failed passwords) | See *Lockout and existing sessions* | Valid until `exp` |
| Signing-key compromise | — | Remove the key from JWKS: tokens with that `kid` fail once caches expire (JWKS `max-age=300`) |

**Accepted risk:** a stolen access token, or one belonging to a user who just signed out or was
suspended, works for up to 15 min. Mitigations: short TTL, in-memory storage in the web client (no
`localStorage`), HTTPS only. Recorded in ARCHITECTURE.md *Accepted risks*.

**Planned (Phase 6): denylist in Redis**, checked by the Gateway (which already uses Redis):
* Add a `sid` claim (the refresh-token family id) to access tokens. Sign-out and reuse detection then
  deny the whole session by `sid`, without needing each token's `jti`.
* Suspension writes a per-user "not before" timestamp: tokens with `iat` earlier than it are refused.
* Entries expire with the longest possible token lifetime (15 min), so the denylist stays small.
* Open decision: fail-closed or fail-open when Redis is unavailable (the rate limiter fails open today).

**Lockout and existing sessions (under review: finding AT6-2).** Lockout is triggered by
*unauthenticated* input: anyone who knows an e-mail address can lock it. Today a locked account's
next refresh revokes its family, so a third party can sign a user out (within ≤ 15 min) and keep them
out for the lock duration; revoking all sessions immediately on lock would make that instant.
Proposed: lockout blocks **password sign-in only**; sessions that prove possession of a valid refresh
token continue. Suspension (an authenticated administrative decision) keeps revoking immediately.

## Consequences
* No additional IdP to run. Full control over the token model.
* We own security-critical code, so it requires focused review and tests (Phase 1 + Phase 6).
* MFA and social login are not in scope until an IdP migration or extension.
