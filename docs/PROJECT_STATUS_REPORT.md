# FoodFlow — Project Status Report (Read-Only Audit)

| | |
|---|---|
| **Audit date** | 2026-11-10 |
| **Repository** | `FoodFlow` (monorepo; paths below are relative to its root) |
| **Method** | Source code, configuration, migrations, test sources, existing Surefire/Failsafe/JaCoCo reports and Git state were inspected. Safe checks were re-run: `backend/mvnw -o verify` (all modules) and the frontend's `vitest run`, `tsc -b --noEmit` and `eslint .`. Documentation was **not** taken as evidence of implementation. |
| **Changes made** | None, apart from creating this file. Re-running the build regenerated files under `target/`, which is git-ignored. |

---

## 0. Executive summary

* FoodFlow is at the **end of Phase 1 of 8** of its own roadmap (`docs/architecture/ARCHITECTURE.md` §12).
* The parts that exist are **well engineered and unit-tested**:
  * Identity (user-service)
  * API gateway
  * Shared platform libraries (including a producer-side transactional outbox)
  * A customer sign-in/sign-up web UI
  * Local infrastructure definitions
  * CI definitions
* **None of the business domain exists yet.** Restaurant, Menu, Cart, Order, Payment, Delivery and Notification services have not been started. There are no directories, no modules and no code for them.
* **Nothing has been committed.** The `main` branch has no commits and there is no remote, so the GitHub Actions pipelines have never run.
* **All 17 integration tests have never executed.** They need Docker, which is not installed on this machine. Persistence, row locking, Flyway migrations, Kafka publishing and Redis rate limiting have never been verified against real infrastructure.
* **Overall weighted completion is 27.5%.** MVP-scope completion is 30.9%. The method is in §10.

---

## 1. Project structure

### 1.1 Modules and services

| Module | Type | Responsibility | State |
|---|---|---|---|
| `backend/pom.xml` | Maven aggregator | Builds every backend module. Seven future service modules are commented out. | Real |
| `backend/foodflow-parent` | Parent POM | Spring Boot 4.1.2, Spring Cloud 2025.1.4 and third-party versions. Enforcer (Java 21+, Maven 3.9+), Failsafe and JaCoCo. | Real |
| `backend/libs/foodflow-event-contracts` | Library | Avro schemas and generated classes: `EventMetadata`, `UserRegistered`. `Topics` holds only `USER_EVENTS`. | Real, minimal (1 event) |
| `backend/libs/foodflow-platform-core` | Library | Unresolved-placeholder config guard, shared JWT issuer/audience validation, correlation-id constants. | Real |
| `backend/libs/foodflow-platform-starter` | Spring Boot starter | RFC 9457 `GlobalExceptionHandler` and `DomainException` hierarchy, resource-server security auto-config (roles → authorities), problem-JSON 401/403 handlers, `CorrelationIdFilter`, `UuidV7`, `PageResponse`, JDBC transactional outbox (writer, relay, Avro codec, traceparent). | Real |
| `backend/user-service` (:8081) | Spring Boot service | Registration, login, RS256 JWT issuing, JWKS, rotating refresh tokens, logout, profile, addresses, admin user search and status changes, `UserRegistered` event via outbox. | **Real, code-complete** |
| `backend/api-gateway` (:8080) | Spring Cloud Gateway (WebFlux) | Routes to user-service, edge JWT validation (JWKS), CORS allow-list, Redis rate limiting (3 tiers), request-size limit, correlation id, `/internal/**` deny. | **Real** |
| `frontend/apps/customer-web` (:5173) | React 19 + TypeScript + Vite | Login, register, auth guards, silent refresh (cross-tab Web Locks), placeholder home page. | Real, auth only |
| `infra/docker` | Docker Compose | PostgreSQL 17 + PostGIS, MongoDB 8, Redis 8.6, Kafka 4.3.2 (KRaft) with topic init, Apicurio Registry and UI, Kafka UI, Grafana otel-lgtm. | Defined, not run here |
| `.github/workflows` | GitHub Actions | Reusable Java pipeline (verify, fail on skipped ITs, buildpack image to GHCR), gateway, user-service and frontend workflows. Dependabot. | Defined, **never executed** |
| `docs/` | Docs | `ARCHITECTURE.md`, 10 ADRs, generated `user-service.openapi.json`. | Real |
| restaurant / menu / cart / order / payment / delivery / notification services | — | — | **Do not exist** |
| partner-portal, courier-pwa, admin-console apps | — | — | **Do not exist** |
| `infra/k8s`, `infra/terraform`, `docs/events` (AsyncAPI) | — | Mentioned in the README and architecture docs | **Do not exist** |

### 1.2 Git state

| Item | Value |
|---|---|
| Repository root | `FoodFlow/` (user-service has no nested `.git`) |
| Current branch | `main` (unborn: *"your current branch 'main' does not have any commits yet"*) |
| Commits | **0** |
| Remotes | **0** |
| Stashes | 0 |
| Uncommitted (untracked, not ignored) files | **217**. Every project file is uncommitted. |
| Staged / modified tracked files | 0 / 0 (nothing is tracked) |

Untracked files by area:

| Area | Files |
|---|---|
| backend/user-service | 87 |
| backend/libs | 50 |
| frontend | 34 |
| backend/api-gateway | 15 |
| docs | 12 |
| infra/docker | 5 |
| .github | 5 |
| Root and backend build files | 9 |

Files that are correctly git-ignored:

* `.idea/`
* `HELP.md`
* `.claude/settings.local.json`
* `target/`
* `dist/`
* `node_modules/`
* `.env`

**Repository hygiene:** `backend/user-service` still contains Spring Initializr leftovers that would be committed:

* its own `mvnw`, `mvnw.cmd`, `.mvn/` (duplicates of `backend/mvnw`)
* `.gitignore`, `.gitattributes`
* `.mcp.json` (a local IDE MCP URL, not a secret)

---

## 2. Backend (user-service and api-gateway)

| Capability | Evidence | Assessment |
|---|---|---|
| Registration | `AuthController.register` and `AuthService.register`. E-mail normalization, duplicate check before and inside the transaction, unique-index race handled, self-assignable `CUSTOMER` / `RESTAURANT_OWNER` / `COURIER` (never `ADMIN`), signs in immediately. | Implemented and unit-tested |
| Login | `AuthService.login`. Snapshot read, hash outside the transaction, timing equalization for unknown e-mails, lockout (5 attempts / 15 min) counted under a row lock, suspension check, rehash-on-upgrade. | Implemented and unit-tested |
| JWT | `JwtAccessTokenIssuer`: RS256, 15 min, `iss`, `sub`, `aud`, `iat`, `nbf`, `exp`, `jti`, `roles`, `kid`. `JwtKeyMaterial` loads a PEM key or an ephemeral key (local only), plus previous public keys for rotation. `JwksController` serves `/.well-known/jwks.json` (public parameters only). | Implemented. `restaurantIds` and `courierId` claims are not yet present (planned for Phase 2). |
| Refresh tokens | Opaque token stored as a SHA-256 hash. Rotation, family revocation on reuse with a grace window, family lock, HttpOnly + Secure + SameSite=Strict cookie scoped to `/api/v1/auth`. | Implemented. **Expired refresh tokens are never purged** (documented gap). |
| Logout | Revokes the token family and clears the cookie. Idempotent. | Implemented |
| Profile | `GET/PATCH /api/v1/users/me` (name, phone; duplicate phone returns 409). | Implemented. No `ETag`/`If-Match`. |
| Addresses | Full CRUD on `/api/v1/users/me/addresses`. Maximum 20 per user, exactly one default, automatic default reassignment, lat/lng validation. | Implemented. No `ETag`/`If-Match`. |
| Admin APIs | `GET /api/v1/admin/users` (status filter, e-mail prefix, pagination). `PATCH /api/v1/admin/users/{id}/status` (suspending revokes sessions; an admin cannot change their own status). URL rule plus `@PreAuthorize`. | Implemented. **There is no way to create the first ADMIN** (no seed, no CLI; role cannot be self-assigned). Only manual SQL works. |
| E-mail verification / password reset | The `email_verified` column exists but nothing sets or enforces it. No endpoints. | **Not started** (planned for Phase 5) |
| Database integration | JPA (`ddl-auto: validate`), Hikari, virtual threads, open-in-view off. | Implemented. Never run against real PostgreSQL here. |
| Migrations | Flyway `V1__create_user_schema.sql`: users, user_roles, addresses, refresh_tokens, outbox_events, with check constraints and partial indexes. | Implemented. Never applied in an executed test here. |
| Security | Deny-by-default stateless resource server, problem-JSON 401/403, `Referrer-Policy: no-referrer`, Argon2id (OWASP parameters) via `DelegatingPasswordEncoder`, bounded hashing concurrency (503 `AUTHENTICATION_BUSY`). | Implemented |
| Validation | Bean Validation on all DTOs and query parameters (password 12–128 characters, NIST-style rules). | Implemented |
| Exception handling | `GlobalExceptionHandler` returns RFC 9457 problems with a stable `code` and `correlationId`. Stack traces and messages are never included. | Implemented and tested |
| API docs | springdoc. `OpenApiDocumentationTest` regenerates `docs/api/user-service.openapi.json` (10 paths). Disabled by default outside the local profile. | Implemented |
| Architecture rules | `ArchitectureTest` (ArchUnit, 6 rules). | Implemented |
| Gateway | Explicit routes for user-service only. Edge JWT (JWKS, RS256, issuer and audience), role regex hardening, CORS allow-list from `CORS_ALLOWED_ORIGINS` with credentials, three Redis token-bucket tiers keyed by IP or principal, trusted-proxy client-IP resolver, 256 KB body limit, `/internal/**` denied. | Implemented. Routes for future services are not present (expected). |

---

## 3. Microservices

| Service | Exists? | Evidence |
|---|---|---|
| User | **Yes, real implementation** | 2,863 lines of main code and 2,507 lines of test code |
| API Gateway | **Yes, real implementation** | 524 lines of main code and 492 lines of test code |
| Restaurant | **No** | No directory. Commented out in `backend/pom.xml`. `restaurant_db` and PostGIS are provisioned by compose only. |
| Menu | **No** | No directory. `menu_db` Mongo user is provisioned by compose only. |
| Cart | **No** | No directory |
| Order | **No** | No directory. `order_db` is provisioned only. |
| Payment | **No** | No directory. `payment_db` is provisioned only. |
| Delivery | **No** | No directory. `delivery_db` and PostGIS are provisioned only. |
| Notification | **No** | No directory. `notification_db` is provisioned only. |
| Admin (as a service) | **No.** Admin is designed as endpoints inside each service plus an admin console. Only user-admin endpoints exist. | `AdminUserController` |

There are **no placeholder services**. A codebase search for TODO, FIXME and "not implemented" found nothing in the source. The only placeholder in the product is the customer home page text: *"Restaurant discovery is coming soon."*

---

## 4. Event-driven architecture

| Element | Status | Evidence |
|---|---|---|
| Kafka broker | Defined locally | Compose `apache/kafka:4.3.2` KRaft. Auto-create is disabled. `create-topics.sh` creates 7 domain topics. |
| Producer configuration | Implemented | user-service `acks=all`, idempotence on, bounded timeouts |
| Avro contracts | **1 business event of about 28 designed** | `UserRegistered.avsc` plus `EventMetadata.avsc`. `UserRegisteredSchemaTest`. The architecture lists 27 more events (UserEmailVerified, Order*, Payment*, Delivery*, and others) that do not exist. |
| Schema Registry | Defined and configured | Apicurio 3.3.4 (in-memory locally). `RecordIdStrategy`. Auto-register is off by default and on only in the local profile. **No CI step registers schemas** (documented gap), so a production deployment would park or block outbox rows. |
| Transactional outbox | **Implemented (producer side)** | `JdbcOutboxWriter` writes in the caller's transaction. `OutboxRelay`: advisory-lock single publisher, ordered, ack-bounded, exponential backoff, poison rows parked, retention purge, Micrometer metrics. Unit tests: `OutboxRelayTest` (6). The integration test `UserRegisteredOutboxIT` has **never run**. |
| Event producers | 1 | `OutboxUserEventPublisher.userRegistered`. No `UserDeactivated` event, although suspension is implemented. |
| Event consumers | **None** | No `@KafkaListener` anywhere. `UserRegistered` is published but nothing consumes it. |
| Consumer idempotency (`processed_events`) | **Not started** | No table, no code |
| Retries / dead-letter topics | **Not started** | No `@RetryableTopic` or DLT handler. The only mention is a comment in `create-topics.sh`. |
| Parked-row replay tooling | Not started | Documented gap |
| AsyncAPI specs (`docs/events/`) | Not started | Directory does not exist |

---

## 5. Frontend (`frontend/apps/customer-web`)

| Area | Status | Evidence |
|---|---|---|
| Stack | Implemented | React 19.3, TypeScript 6, Vite 8, pnpm 12 workspace with supply-chain policy, React Router 7, TanStack Query, Zustand, React Hook Form + Zod |
| Routing | Implemented (3 routes) | `/login`, `/register` (GuestOnly), `/` (RequireAuth), `*` (404) |
| Authentication | Implemented | Access token held in memory only. Silent refresh through the HttpOnly cookie. Refresh serialized in-tab (shared promise) and cross-tab (Web Locks). 401 triggers one retry. Problem-details error mapping. |
| API integration | Partial | Hand-written `apiRequest` client. Only auth and `GET /users/me` are used. **No generated OpenAPI client** (documented gap). The Vite proxy routes `/api` to the gateway. |
| Forms | Implemented (2) | Login and register with Zod schemas. Registration always sends `accountType: 'CUSTOMER'`. |
| Profile / address UI | **Not started** | Backend APIs exist, but there are no screens |
| Dashboards | **Not started** | No partner, courier or admin applications |
| Discovery, menu, cart, checkout, tracking, order history | **Not started** | — |
| UI components | Minimal | `TextField`, a 150-line `styles.css` |
| Responsive design | Basic only | A single-column layout capped at `max-width: 28rem`, plus a dark-mode media query. There are no breakpoints. |
| Tests | **20/20 pass** (re-run during this audit) | `auth.test.tsx` (11) and `http.test.ts` (9) using Vitest, Testing Library and MSW. `tsc` and `eslint` are clean. No coverage configured. No E2E (Playwright) tests. |

---

## 6. Infrastructure

| Item | Status | Notes |
|---|---|---|
| Docker Compose | Defined (not verified: **Docker is not installed on this machine**) | Ports bound to 127.0.0.1. Credentials are required from `.env` (`:?` guards). Healthchecks. Image versions pinned. |
| PostgreSQL | Defined | `postgis/postgis:17-3.7`. Init script creates 5 databases with one role each and enables PostGIS where needed. |
| MongoDB | Defined | `mongo:8.0.34`. Users for `menu_db` and `notification_db`. |
| Redis | Defined and used by the gateway | AOF enabled. No password locally. |
| Kafka | Defined | Single-node KRaft, plaintext |
| Schema Registry | Defined | Apicurio, in-memory, `CORS *` (dev only) |
| Grafana / observability | Defined | `grafana/otel-lgtm` (Prometheus, Loki, Tempo, Collector). Services have OTLP export configuration. **No dashboards or alert rules are committed.** |
| Application containers | Configured in CI only | Cloud Native Buildpacks via `spring-boot:build-image`. No Dockerfiles. Apps are not part of compose. |
| CI (GitHub Actions) | Defined, **never run** | Path-filtered per service. Fails if ITs were skipped. Uploads reports. Pushes images to GHCR on `main`. Frontend verify job. Dependabot for Maven, npm, Actions and compose. |
| CD | **Not started** | No deploy jobs, no environments, no OIDC to AWS |
| Security scanning in CI | **Not started** | No CodeQL, gitleaks or image scanning. Actions are pinned by tag, not by SHA. |
| AWS readiness | **Not started** | No `infra/terraform`, no `infra/k8s`, no Helm charts. Readiness: env-var-only configuration, graceful shutdown, liveness/readiness probes, a PEM key mount for JWT, Redis TLS/password flags. The readiness probe does not include the database (documented gap). |

---

## 7. Testing

Re-executed on 2026-11-10 with `backend/mvnw -o verify`. Result: **BUILD SUCCESS** for all 7 reactor modules.

| Module | Unit tests (Surefire) | Integration tests (Failsafe) | JaCoCo line coverage | Branch coverage |
|---|---|---|---|---|
| foodflow-event-contracts | 2 pass | — | 100% (7/7) | 50% |
| foodflow-platform-core | 7 pass | — | 85.3% (29/34) | 72.7% |
| foodflow-platform-starter | 34 pass | — | 60.0% (279/465) | 48.4% |
| user-service | 87 pass | **16 skipped** | 85.7% (622/726) | 76.2% |
| api-gateway | 19 pass | **1 skipped** | 93.8% (165/176) | 70.5% |
| **Backend total** | **149 pass, 0 fail** | **17 skipped, 0 run** | | |
| customer-web (Vitest) | 20 pass, 0 fail | — | not configured | — |

* **Why the tests were skipped:** every skipped test reports *"disabledWithoutDocker is true and Docker is not available"*. This applies to `@Testcontainers(disabledWithoutDocker = true)` on `AbstractIntegrationTest` and `RateLimitIT`. Docker is not installed (`docker: command not found`).
* **What the skipped ITs would verify:**
  * real Flyway migrations
  * JPA mappings against PostgreSQL (`ddl-auto: validate`)
  * `SELECT … FOR UPDATE` and family-lock concurrency (`AuthConcurrencyIT`)
  * the full auth flow (`AuthFlowIT`)
  * addresses and admin (`AddressAndAdminIT`)
  * outbox publishing to Kafka through Apicurio (`UserRegisteredOutboxIT`)
  * Redis rate limiting (`RateLimitIT`)
* **Coverage caveat:** the platform-starter figure is low because the JDBC outbox store and writer are only exercised by the skipped ITs.
* **Coverage gate:** the documented gate (≥ 80% on domain and application code) is **not enforced**. JaCoCo reports coverage but has no `check` goal.
* **Not present:** contract tests (Pact or Spring Cloud Contract), E2E (Playwright), performance tests (k6), and a frontend coverage report.

---

## 8. Security

| Area | Finding |
|---|---|
| Authentication | Strong design: RS256, 15-minute access tokens, rotating hashed refresh tokens with reuse detection, lockout, timing equalization, bounded Argon2id. Unit-verified. Not verified end-to-end. |
| Authorization | Deny-by-default in both the service and the gateway. Admin protected by a URL rule plus `@PreAuthorize`. Ownership is enforced by scoping every query to the JWT `sub`. |
| ⚠ Role self-assignment | Anyone can self-register as `RESTAURANT_OWNER` or `COURIER`, with no approval workflow. This is harmless today because no partner or courier endpoints exist. It **must** be gated (approval or invitation) before Phase 2 gives those roles any power. |
| ⚠ Admin bootstrap | There is no supported way to create the first administrator. |
| Password storage | Argon2id (m = 19 MiB, t = 2, p = 1) with bcrypt fallback and upgrade on login. Good. |
| Secrets | No secrets in the repository. `.env`, `*.pem`, `*.key` and similar are git-ignored. `.env.example` contains only `change-me`. `UnresolvedPlaceholderGuard` fails startup on missing variables. The JWT key comes from a file mount. An ephemeral key is allowed only in the local profile. |
| CORS | Gateway allow-list from an environment variable, credentials allowed, explicit headers. Services rely on the gateway. Local Apicurio uses `*` (dev only). |
| JWT handling (frontend) | Token in memory only, cookie HttpOnly and SameSite=Strict. Source maps are `hidden`. |
| CSRF | Disabled (stateless bearer API). Cookie endpoints rely on SameSite=Strict and path scoping. Acceptable and documented. |
| Known accepted risks | E-mail enumeration (409/423). Rate limiter fails open without Redis. JWKS over in-cluster HTTP. Tokens stay valid up to 15 minutes after logout or suspension (no `jti` denylist). |
| Dependency vulnerabilities | **Not assessed.** There is no OWASP Dependency-Check, Snyk, `pnpm audit` step or CodeQL in the build, and the audit environment ran offline. Dependabot is configured but has never run (no remote). |
| Production configuration | Swagger is off by default. Stack traces and messages are never returned. Actuator exposes only `health` and `info` without details. `forward-headers-strategy: native`. Refresh cookie is `Secure` by default. Tracing is sampled at 10%. Kafka, Redis and Postgres TLS are not configured beyond Redis flags. |

---

## 9. Business functionality: end-to-end journeys

| Journey | Works end-to-end? | Detail |
|---|---|---|
| Customer registration and login | **Code-complete, not verified at runtime** | UI → Vite proxy → gateway → user-service → PostgreSQL. All layers are implemented and unit- or slice-tested. A full run needs Docker and has never been observed. Registration also publishes `UserRegistered`. |
| Restaurant discovery | **No** | Not started |
| Menu browsing | **No** | Not started |
| Cart management | **No** | Not started |
| Order placement | **No** | Not started |
| Payment | **No** | Not started |
| Delivery tracking | **No** | Not started |
| Notifications | **No** | Not started. No consumer of `UserRegistered`. |
| Admin operations | **Partial (API only)** | User search and suspension through the API. No admin UI. No first-admin bootstrap. No restaurant approval, refunds or DLT replay. |

---

## 10. Project completion

### 10.1 Weighting method

* Every feature in the architecture scope (§3–§12 of `ARCHITECTURE.md`) is assigned a weight. The weights sum to **100**.
* Weights reflect relative scope and risk. The Order saga and Payment carry the most weight because they hold the money and state-machine logic. Foundation items carry less.
* Each feature earns credit by status:
  * **COMPLETED** = 100% of its weight
  * **PARTIALLY COMPLETED** = 50%
  * **NOT STARTED** = 0%
  * **BLOCKED** = 0%
* No other percentages are estimated. Partial work is always credited at exactly half.
* "COMPLETED" means the code exists and its unit and slice tests pass. Runtime and integration verification is tracked as a separate BLOCKED row.

| # | Feature | Wt | Status | Evidence | Missing work | Priority |
|---|---|---|---|---|---|---|
| 1 | Monorepo and build foundation | 1 | COMPLETED | `foodflow-parent`, aggregator, wrapper. Build succeeds. | Remove duplicate wrapper and Initializr files in user-service | P3 |
| 2 | Shared platform libraries | 3 | COMPLETED | core and starter, 41 tests pass | Coverage gate | P3 |
| 3 | Registration, login, JWT, JWKS, refresh, logout | 5 | COMPLETED | `AuthService`, `AuthController`, `JwtConfiguration`; 45 related unit tests | Refresh-token purge job; `restaurantIds` and `courierId` claims | P1 |
| 4 | E-mail verification and password reset | 2 | NOT STARTED | Column only | Endpoints, tokens table, notification integration | P2 |
| 5 | Profile and addresses API | 2 | COMPLETED | Controllers, services, `UserControllersTest` | ETag/If-Match | P3 |
| 6 | Admin user management | 1 | PARTIALLY COMPLETED | `AdminUserController` | First-admin bootstrap, `UserDeactivated` event, admin UI | P1 |
| 7 | user_db migrations | 1 | COMPLETED | `V1__create_user_schema.sql` | Run them (row 26) | P1 |
| 8 | API gateway | 4 | COMPLETED | Routes, security, rate limit; 19 tests | Routes for new services as they arrive | P1 |
| 9 | Outbox, Avro, registry (producer) | 3 | COMPLETED | `OutboxRelay`, `JdbcOutboxWriter`, `AvroPayloadCodec` | Schema registration in CI; parked-row replay tooling | P1 |
| 10 | Kafka consumers, idempotency, retry, DLT | 3 | NOT STARTED | No `@KafkaListener` | Consumer starter: `processed_events`, `@RetryableTopic`, DLT | P0 |
| 11 | Event contracts (all domains) | 2 | PARTIALLY COMPLETED | 1 of about 28 events | Restaurant, Menu, Order, Payment and Delivery schemas; AsyncAPI | P0 |
| 12 | Restaurant service (PostGIS) | 7 | NOT STARTED | — | Whole service | P0 |
| 13 | Menu service (MongoDB) | 6 | NOT STARTED | — | Whole service | P0 |
| 14 | Cart service (Redis) | 5 | NOT STARTED | — | Whole service | P0 |
| 15 | Order service and saga | 10 | NOT STARTED | — | Whole service | P0 |
| 16 | Payment service (Stripe) | 8 | NOT STARTED | — | Whole service | P0 |
| 17 | Delivery service | 6 | NOT STARTED | — | Whole service | P1 |
| 18 | Notification service | 4 | NOT STARTED | — | Whole service | P1 |
| 19 | Customer web: auth | 3 | COMPLETED | 20 Vitest tests, tsc and eslint clean | — | — |
| 20 | Customer web: discovery to tracking, profile, addresses | 6 | NOT STARTED | Placeholder home page | All screens; generated API client; responsive layout | P0 |
| 21 | Partner portal, courier PWA, admin console | 3 | NOT STARTED | — | Three apps | P1–P2 |
| 22 | Local infrastructure (compose) | 2 | PARTIALLY COMPLETED | `docker-compose.yml` | Never started; apps not containerized in compose | P0 |
| 23 | CI pipelines | 2 | PARTIALLY COMPLETED | 4 workflows | Never run (no commits or remote); security scans; SHA pinning; CD | P0 |
| 24 | Cloud (Terraform, EKS, Helm, CD) | 3 | NOT STARTED | Directories absent | Everything in Phase 7 | P2 |
| 25 | Observability | 2 | PARTIALLY COMPLETED | OTLP config, LGTM container, outbox metrics | Dashboards, alerts, SLOs | P2 |
| 26 | Integration verification (Testcontainers) | 2 | **BLOCKED** | 17 of 17 ITs skipped | Install Docker, or run CI | **P0** |
| 27 | E2E, contract and performance tests | 2 | NOT STARTED | — | Playwright, Pact, k6 | P1 |
| 28 | Security hardening | 2 | PARTIALLY COMPLETED | Strong auth design | Role-approval gating, jti denylist, dependency/SAST scanning, TLS | P1 |

### 10.2 Completion percentages

| Measure | Calculation | Result |
|---|---|---|
| **Overall** | COMPLETED weights (1+3+5+2+1+4+3+3 = 22) + ½ × PARTIAL weights (1+2+2+2+2+2 = 11 → 5.5) | **27.5 / 100 = 27.5%** |
| **MVP scope** (rows 1–3, 5, 7–17, 19, 20, 22, 23, 26; weight 81) | 22 completed + ½ × (2 + 2 + 2) = 25 | **25 / 81 = 30.9%** |
| By roadmap phase | Phase 0 done. Phase 1 code-complete but IT-blocked. Phases 2–7 at 0%. | **1 of 8 phases done; the 2nd is blocked on verification** |

The overall figure ranges from **22%** (credit only COMPLETED) to **33%** (credit PARTIAL in full). The figure is driven by the services that have not been started, which carry 46 of the 100 weight points.

---

## 11. Roadmap (prioritized)

Phase numbers follow the project's own roadmap. Each step depends on the previous ones unless noted.

### Step 0: Close Phase 1 (immediate, about 2–4 days)

| Task | Depends on | Acceptance criteria |
|---|---|---|
| 0.1 Install Docker Desktop and run `docker compose up -d` | — | All compose services are healthy. `kafka-init` lists 7 topics. |
| 0.2 Run `./mvnw verify` with Docker | 0.1 | **17/17 ITs pass, 0 skipped.** Migrations apply. The outbox event appears on `foodflow.user.events.v1`. |
| 0.3 Manual E2E: register, log in, refresh, log out through `:5173` → `:8080` → `:8081` | 0.1 | Cookie set and rotated. Profile loads. Rate limit returns 429 when exceeded. |
| 0.4 Clean up and make the first commit (remove Initializr duplicates in user-service after review); create the GitHub remote | — | First commit pushed. All 3 workflows green on GitHub, including the "no skipped ITs" gate. |
| 0.5 First-admin bootstrap (env-driven seed or CLI runner); `UserDeactivated` event | 0.2 | An admin can be created without SQL. Suspension publishes an event. |
| 0.6 Gate `RESTAURANT_OWNER` and `COURIER` self-registration (pending state, admin approval) | 0.5 | A new owner or courier cannot act until approved. Tests cover this. |

### Step 1: Event backbone completion (can run alongside Phase 2, about 1 week)

| Task | Acceptance criteria |
|---|---|
| Consumer support in the platform starter: `processed_events` idempotency, `@RetryableTopic` with backoff, `.dlt` | A duplicate event is processed once. A poison event lands in the DLT. Covered by an IT. |
| Schema registration from CI; outbox parked-row replay endpoint or tool | CI registers schemas with BACKWARD compatibility. A breaking schema fails the build. |
| AsyncAPI in `docs/events/` | One spec per topic |

### Step 2: Phase 2, Catalog (about 3–4 weeks)

* **restaurant-service:** PostGIS, geo search, hours, availability, `restaurantIds` JWT claim, events.
* **menu-service:** MongoDB, categories, items, variants, add-ons, availability, internal price check.
* **customer-web:** discovery and menu pages, generated TypeScript client, responsive breakpoints.
* **Partner portal:** onboarding and menu management.
* **Acceptance:** a customer browses nearby restaurants and menus through the gateway; ITs pass for both services.

### Step 3: Phase 3, Cart (about 1–2 weeks)

* **cart-service:** Redis with 7-day TTL, price check against menu, invalidation on menu events.
* **Cart UI:** includes the "replace cart" prompt.
* **Acceptance:** the cart persists across sessions and stale prices are flagged.

### Step 4: Phase 4, Order and Payment (about 4–6 weeks; the critical path)

* **order-service:** state machine, orchestrated saga, `saga_state` deadlines, `Idempotency-Key`.
* **payment-service:** Stripe test mode authorize, capture, void and refund; webhook signature verification; ledger.
* **Partner order queue:** SSE.
* **Checkout UI:** Stripe payment element.
* **Acceptance:** the happy path and all compensation paths (payment failed, restaurant rejected or timed out) pass E2E. No double charge under retries.

### Step 5: Phase 5, Delivery and Notification (about 3–4 weeks)

* **delivery-service:** courier assignment with Redis GEO, SSE tracking.
* **notification-service:** MongoDB, templates, in-app feed, e-mail; e-mail verification and password reset in user-service.
* **Courier PWA.**
* **Acceptance:** live tracking E2E, and the signup verification e-mail is received.

### Step 6: Phase 6, Hardening (about 2–3 weeks)

* `jti` denylist
* CodeQL, gitleaks, dependency and image scanning
* SHA-pinned Actions
* k6 tests against SLOs
* Grafana dashboards and alerts
* JaCoCo coverage gate
* Playwright E2E
* **Acceptance:** SLOs are met under target load and security scans are clean.

### Step 7: Phase 7, Cloud rollout (about 3–4 weeks)

* Terraform for VPC, EKS, RDS, ElastiCache, MSK, Atlas, Secrets Manager and CloudFront
* Helm charts
* OIDC CD with dev → staging → prod promotion and canary releases
* Readiness probe policy
* **Acceptance:** staging matches production and the go-live checklist is signed off.

---

## 12. Final questions

**What is completely finished?**
* Build foundation and shared platform libraries.
* user-service: registration, login, JWT/JWKS, refresh rotation, logout, profile, addresses, admin user search and suspension, Flyway schema, RFC 9457 errors, OpenAPI.
* API gateway: routing, edge JWT, CORS, rate limiting.
* Producer-side transactional outbox with Avro.
* Customer-web sign-up and sign-in.

These are complete at the code and unit-test level (169 tests pass: 149 backend + 20 frontend).

**What is partially finished?**
* Admin operations (API only, no bootstrap or UI)
* Event contracts (1 event)
* Docker Compose (defined, never run)
* CI (defined, never run)
* Observability (wiring only)
* Security hardening

**What has not been started?**
* Restaurant, Menu, Cart, Order, Payment, Delivery and Notification services
* Kafka consumers, idempotency and DLT
* E-mail verification and password reset
* All customer screens beyond auth
* Partner, courier and admin apps
* Terraform, Kubernetes and CD
* E2E, contract and performance tests
* Vulnerability scanning

**Which services actually run?**
* user-service and api-gateway **build and package** (executable JARs exist), and their Spring contexts start in tests.
* Neither has been **observed running against real infrastructure**. Both need PostgreSQL, Kafka and Apicurio (user-service) or Redis (gateway), and Docker is not installed.
* The customer web app builds and its tests pass.

**Can the application run end-to-end?**
* **No, not as a food-ordering application.** The only journey that exists is account sign-up and sign-in, and even that has not been executed end-to-end on this machine.
* After installing Docker it is expected to run, pending the 17 integration tests.

**What is preventing an MVP?**
1. Docker or a CI run to verify Phase 1 (blocker).
2. No catalog, cart, order or payment services. These are about 36 of the 100 weight points and the whole purchase path.
3. No Kafka consumer, idempotency or DLT support, which the saga depends on.
4. No customer screens beyond auth.

**What is preventing production deployment?**
* Everything listed for the MVP, plus:
  * no commits or remote, so CI has never run
  * no Terraform, Kubernetes or Helm and no CD
  * no schema registration in CI
  * no dependency, SAST or image scanning
  * no `jti` denylist
  * ungated owner and courier self-registration
  * no admin bootstrap
  * no dashboards or alerts
  * no load testing
  * no persistent registry storage or TLS for data stores

**What is the immediate next task?**
* **Install Docker and run `backend/mvnw verify` until all 17 integration tests pass. Then make the first commit and push, so the CI pipelines run** (Roadmap steps 0.1–0.4).
* Building Phase 2 on top of persistence and messaging code that has never run would compound risk.

**What is the estimated remaining development effort?**
* About **18–25 engineer-weeks** for one experienced full-stack engineer (the sum of the items below):
  * Step 0: about 0.5
  * Step 1: about 1
  * Phase 2: 3–4
  * Phase 3: 1–2
  * Phase 4: 4–6
  * Phase 5: 3–4
  * Phase 6: 2–3
  * Phase 7: 3–4
* The **MVP** (Steps 0–4) is roughly **10–14 engineer-weeks**.
* This is an estimate, not a measurement. It is calibrated against what exists today: Phase 1 is about 5,000 lines of main code, about 3,900 lines of backend test code and about 1,150 lines of frontend code. Each remaining service is assumed to be comparable to or larger than user-service, with Order and Payment substantially larger because of saga, compensation and webhook logic.
