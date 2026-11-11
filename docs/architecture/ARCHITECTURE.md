# FoodFlow — Architecture

> Status: **Approved** (2026-11-07). Decisions are recorded as ADRs in [`docs/adr`](../adr).
> This document is the living reference; change it via PR together with a new/superseding ADR.

## 1. Context & goals

FoodFlow is an enterprise food ordering and delivery platform serving four audiences:
customers, restaurant partners, couriers and operations/admin staff.

Quality goals (in priority order):

1. **Correctness of money and orders** — no lost orders, no double charges.
2. **Independent deployability** — any service ships without coordinating with others.
3. **Security** — zero-trust between services, least-privilege data access.
4. **Observability** — any order is traceable end-to-end across services.
5. **Scalability** — read-heavy catalog and bursty checkout scale independently.

## 2. System overview

```
                     ┌──────────────────────────────────────────────────────┐
                     │                 CLIENTS (React + TypeScript)          │
                     │  Customer Web │ Partner Portal │ Courier PWA │ Admin  │
                     └──────────────────────────┬───────────────────────────┘
                                                │ HTTPS (REST + SSE)
                                     ┌──────────▼──────────┐
                                     │  CloudFront + WAF   │
                                     └──────────┬──────────┘
                     ┌──────────────────────────▼──────────────────────────┐
                     │           API GATEWAY (Spring Cloud Gateway)         │
                     │  JWT validation (JWKS) · routing · CORS · rate limit │
                     └─┬───────┬────────┬────────┬────────┬────────┬───────┘
       ┌───────────────┘       │        │        │        │        └──────────────┐
       ▼                       ▼        ▼        ▼        ▼                       ▼
 ┌───────────┐  ┌────────────┐ ┌──────┐ ┌──────┐ ┌───────────────┐        ┌──────────┐
 │   USER    │  │ RESTAURANT │ │ MENU │ │ CART │ │     ORDER     │        │ DELIVERY │
 │ JWT issuer│  │            │ │      │ │      │ │ saga orchestr.│        │          │
 └─────┬─────┘  └─────┬──────┘ └──┬───┘ └──┬───┘ └───────┬───────┘        └────┬─────┘
       ▼              ▼           ▼        ▼             ▼                     ▼
  PostgreSQL   PostgreSQL+PostGIS MongoDB  Redis     PostgreSQL         PostgreSQL+Redis GEO
       │              │           │                      │      ┌─────────┐    │
       └──── outbox ──┴───────────┴──────────────────────┴──────┤ PAYMENT ├────┘
                                  ▼                             │Postgres │──► Stripe
   ╔══════════════════════════════════════════════════════════╗ └────┬────┘
   ║        APACHE KAFKA (KRaft) + Apicurio Schema Registry     ║◄─────┘
   ╚══════════════════════════════╤═════════════════════════════╝
                                  ▼
                         ┌─────────────────┐
                         │  NOTIFICATION   │──► Email / SMS / Push / SSE
                         │    MongoDB      │
                         └─────────────────┘
```

### Communication rules

* **Synchronous REST** only for user-facing queries and checks the caller must wait for
  (Cart → Menu price check, Order → Cart snapshot at checkout).
* **State changes that cross service boundaries travel as Kafka events** published through the
  **Transactional Outbox** (no dual writes).
* **No shared databases.** A service never reads another service's tables.
* **Service discovery is Kubernetes DNS.** No Eureka / Config Server; Spring Cloud is used only by
  the API Gateway.
* `/internal/**` endpoints are cluster-internal only (blocked at the Gateway and by NetworkPolicy).

## 3. Services & data ownership

| Service | Port | Responsibility | Store |
|---|---|---|---|
| api-gateway | 8080 | Routing, edge JWT validation, rate limiting, CORS, correlation id | Redis (rate-limit buckets) |
| user-service | 8081 | Registration, login, JWT issuing, refresh tokens, profiles, addresses, roles | PostgreSQL `user_db` |
| restaurant-service | 8082 | Restaurant profiles, owners, hours, geo search, availability | PostgreSQL + PostGIS `restaurant_db` |
| menu-service | 8083 | Categories, items, variants, add-ons, pricing, item availability | MongoDB `menu_db` |
| cart-service | 8084 | Active carts, line items, price snapshot | Redis (TTL 7d) |
| order-service | 8085 | Order state machine, saga orchestration, restaurant accept/reject | PostgreSQL `order_db` |
| payment-service | 8086 | Authorize/capture/void/refund, provider webhooks, ledger (no card data) | PostgreSQL `payment_db` |
| delivery-service | 8087 | Couriers, assignment, delivery status, live location | PostgreSQL + PostGIS `delivery_db`, Redis GEO |
| notification-service | 8088 | Templates, multi-channel dispatch, in-app feed | MongoDB `notification_db` |

### 3.1 Schema conventions (PostgreSQL)

* Migrations: **Flyway**, owned by the service. `ddl-auto=validate` everywhere.
* IDs: **UUIDv7**. Every table has `created_at`, `updated_at`, `version` (optimistic locking).
* Money: `amount_minor BIGINT` + `currency CHAR(3)` — never floating point.
* Every event-producing service has `outbox_events`; every consuming service has `processed_events`.

### 3.2 Key schemas

**user_db** (implemented in Phase 1: `user-service/src/main/resources/db/migration/V1__create_user_schema.sql`)
```
users(id PK, email UNIQUE, phone UNIQUE (partial), password_hash, full_name,
      status[ACTIVE|SUSPENDED], email_verified, failed_login_attempts, locked_until,
      last_login_at, created_at, updated_at, version)
user_roles(user_id FK, role[CUSTOMER|RESTAURANT_OWNER|COURIER|ADMIN], PK(user_id, role))
addresses(id PK, user_id FK, label, line1, line2, city, postal_code, country, latitude, longitude,
          is_default [partial unique index: one default per user], created_at, updated_at, version)
refresh_tokens(id PK, user_id FK, family_id, token_hash UNIQUE (SHA-256), issued_at, expires_at,
               revoked_at, replaced_by, user_agent, ip_address, version)
outbox_events(seq IDENTITY PK, id UNIQUE, aggregate_type, aggregate_id, event_type, topic,
              payload_type, payload BYTEA (Avro single-object), correlation_id, traceparent,
              created_at, published_at, attempts, last_error)
-- Phase 5 (with notification-service): verification_tokens(id, user_id, type[EMAIL|PASSWORD_RESET], token_hash, expires_at, used_at)
```
Temporary lockout is time-based (`locked_until`), separate from the administrative `status`.

**restaurant_db**
```
restaurants(id, owner_user_id, name, slug UNIQUE, description, cuisine_tags TEXT[], phone,
            address_json, location GEOGRAPHY(Point) [GIST], status[DRAFT|ACTIVE|SUSPENDED],
            is_accepting_orders, avg_prep_minutes, rating_avg, rating_count, ...)
opening_hours(restaurant_id, day_of_week, opens_at, closes_at)
outbox_events(...)
```

**menu_db (MongoDB)**
```
menus { _id, restaurantId (unique), version, updatedAt,
        categories: [ { id, name, sortOrder,
          items: [ { id, name, description, imageUrl, priceMinor, currency, available, dietaryTags,
                     variants: [ {id, name, priceDeltaMinor} ],
                     addonGroups: [ {id, name, min, max, options: [...]} ] } ] } ] }
```

**cart (Redis)**
```
cart:{userId} → { restaurantId, items:[{lineId, menuItemId, variantId, addonIds, qty, unitPriceMinor}], updatedAt }  TTL 7d
```

**order_db**
```
orders(id, order_number UNIQUE, customer_id, restaurant_id, status, subtotal_minor,
       delivery_fee_minor, tax_minor, total_minor, currency, delivery_address_json,
       placed_at, idempotency_key UNIQUE, version)
order_items(id, order_id FK, menu_item_id, name_snapshot, unit_price_minor, qty, options_json)
order_status_history(id, order_id, from_status, to_status, reason, actor, occurred_at)
saga_state(order_id PK, step, attempts, last_error, deadline_at, updated_at)
outbox_events(...), processed_events(event_id PK, processed_at)
```

**payment_db**
```
payments(id, order_id UNIQUE, customer_id, provider, provider_intent_id,
         status[PENDING|AUTHORIZED|CAPTURED|FAILED|VOIDED|REFUNDED],
         amount_minor, currency, idempotency_key, version)
payment_transactions(id, payment_id, type[AUTH|CAPTURE|VOID|REFUND], amount_minor,
                     provider_ref, status, raw_response JSONB)
webhook_events(provider_event_id PK, type, payload JSONB, received_at, processed_at)
outbox_events(...), processed_events(...)
```

**delivery_db** (+ Redis `couriers:geo` GEOSET, `courier:{id}:state`)
```
couriers(id, user_id UNIQUE, vehicle_type, status[OFFLINE|AVAILABLE|ON_DELIVERY], rating)
deliveries(id, order_id UNIQUE, courier_id, status[PENDING_ASSIGNMENT|ASSIGNED|PICKED_UP|DELIVERED|FAILED],
           pickup_location, dropoff_location, eta, assigned_at, picked_up_at, delivered_at)
delivery_tracking(id, delivery_id, lat, lng, recorded_at)  -- partitioned monthly
outbox_events(...), processed_events(...)
```

**notification_db (MongoDB)**: `templates`, `notifications {userId, channel, type, payload, status, attempts, sentAt}`, `user_preferences`.

## 4. Event-driven communication

Conventions (ADR-0004, ADR-0005):

* Topic: `foodflow.<domain>.events.v1`; **key = aggregate id** (ordering per aggregate).
* Envelope (CloudEvents-style): `eventId`, `eventType`, `source`, `occurredAt`, `traceparent`,
  `schemaVersion`, `data`.
* Serialization: **Avro** + **Apicurio Registry**, BACKWARD compatibility enforced.
* Producers: idempotent, `acks=all`, via outbox relay. Consumers: at-least-once + idempotent
  (`processed_events`). Failures: `@RetryableTopic` with backoff → `.dlt`.

| Topic | Producer | Events | Consumers |
|---|---|---|---|
| `foodflow.user.events.v1` | User | UserRegistered, UserEmailVerified, UserRoleGranted, UserDeactivated | Notification, Delivery, Restaurant |
| `foodflow.restaurant.events.v1` | Restaurant | RestaurantCreated, RestaurantStatusChanged, RestaurantAvailabilityChanged | Menu, Order, Cart |
| `foodflow.menu.events.v1` | Menu | MenuItemPriceChanged, MenuItemAvailabilityChanged | Cart |
| `foodflow.order.events.v1` | Order | OrderPlaced, OrderAwaitingRestaurant, OrderConfirmed, OrderRejected, OrderReadyForPickup, OrderCancelled, OrderCompleted | Payment, Delivery, Notification, Cart |
| `foodflow.payment.events.v1` | Payment | PaymentAuthorized, PaymentFailed, PaymentCaptured, PaymentVoided, PaymentRefunded | Order, Notification |
| `foodflow.delivery.events.v1` | Delivery | CourierAssigned, DeliveryPickedUp, DeliveryCompleted, DeliveryFailed | Order, Notification |
| `foodflow.delivery.location.v1` | Delivery | CourierLocationUpdated (24h retention) | Notification |

### 4.1 Order saga (orchestrated by Order Service — ADR-0006)

```
POST /orders (Idempotency-Key) → ORDER: PENDING_PAYMENT ──OrderPlaced──► PAYMENT authorize
  ├─ PaymentFailed      → ORDER: CANCELLED
  └─ PaymentAuthorized  → ORDER: AWAITING_RESTAURANT → partner portal (SSE)
       ├─ reject / 5-min timeout → OrderRejected → PAYMENT void → ORDER: CANCELLED
       └─ accept → OrderConfirmed → PAYMENT capture
                                 └► DELIVERY assign courier (Redis GEO)
            OrderReadyForPickup → DeliveryPickedUp → DeliveryCompleted → ORDER: COMPLETED
```

Customers are never charged for rejected orders: funds are authorized at checkout and captured
only after restaurant acceptance. Each step and compensation is idempotent; timeouts are driven
by `saga_state.deadline_at`.

## 5. Security (ADR-0007)

* **User Service is the token issuer.** It uses Spring Security OAuth2 Resource Server + Nimbus JOSE.
  * Access token: JWT, RS256, 15 min. Claims: `sub`, `roles`, `restaurantIds`, `courierId`,
    `iss`, `aud`, `jti`.
  * Refresh token: opaque, stored hashed, 14 days.
    * Rotation with reuse detection: replaying an old token revokes the whole family.
    * Delivered to web clients in a `HttpOnly; Secure; SameSite=Strict` cookie.
  * JWKS at `/.well-known/jwks.json` with `kid` key rotation. Signing keys live in AWS KMS / Secrets Manager.
* **Defense in depth:** the Gateway validates JWTs at the edge, **and** every service is itself a
  resource server. Sign-out, reuse detection and suspension revoke the refresh-token family at
  once; issued access tokens stay valid until they expire (≤ 15 min). A Redis denylist is planned
  (Phase 6). See ADR-0007, *Access-token revocation*.
* **Authorization**
  * Role checks use URL rules and `@PreAuthorize`.
  * Ownership checks happen in the domain layer.
* **Hardening**
  * Argon2id password hashing via `DelegatingPasswordEncoder`.
  * Login rate limiting and account lockout.
  * Email verification required.
  * CORS allow-list and security headers.
  * gitleaks, CodeQL and dependency scanning in CI.
* **Payments:** provider-hosted card fields (Stripe), webhook signature verification. No card data
  is stored, which keeps us out of PCI DSS scope (SAQ-A).

## 6. REST API conventions & contracts

* Base path `/api/v1/...` (Spring Framework 7 API versioning).
* Errors: RFC 9457 Problem Details.
* Pagination: `?page&size&sort` for lists, cursor-based for feeds.
* `Idempotency-Key` required on `POST /orders` and payment creation.
* `ETag` / `If-Match` on updates.
* Contract-first: OpenAPI 3.1 specs in `docs/api/`, AsyncAPI specs in `docs/events/`.

| Service | Key endpoints |
|---|---|
| User | `POST /auth/register` · `/auth/login` · `/auth/refresh` · `/auth/logout` · `/auth/verify-email` · `/auth/password/forgot` · `/auth/password/reset` · `GET/PATCH /users/me` · `GET/POST/PUT/DELETE /users/me/addresses[/{id}]` · `GET /.well-known/jwks.json` · admin: `GET /admin/users`, `PATCH /admin/users/{id}/status` |
| Restaurant | `GET /restaurants?lat&lng&radiusKm&cuisine&q` · `GET /restaurants/{id}` · `POST /restaurants` · `PUT /restaurants/{id}` · `PATCH /restaurants/{id}/availability` · `PUT /restaurants/{id}/opening-hours` |
| Menu | `GET /restaurants/{id}/menu` · CRUD `.../menu/categories[/{cid}]` · CRUD `.../items[/{itemId}]` · `PATCH .../items/{itemId}/availability` · internal `POST /internal/menu/price-check` |
| Cart | `GET /cart` · `POST /cart/items` · `PATCH /cart/items/{lineId}` · `DELETE /cart/items/{lineId}` · `DELETE /cart` · internal `GET /internal/carts/{userId}` |
| Order | `POST /orders` · `GET /orders` · `GET /orders/{id}` · `POST /orders/{id}/cancel` · partner: `GET /restaurants/{rid}/orders?status=`, `POST /orders/{id}/accept`, `/reject`, `/ready` · `GET /orders/{id}/events` (SSE) |
| Payment | `POST /payments/intents` · `GET /payments/{orderId}` · `POST /payments/webhooks/{provider}` · admin `POST /payments/{id}/refund` |
| Delivery | courier: `PATCH /couriers/me/status`, `POST /couriers/me/location`, `GET /couriers/me/deliveries`, `POST /deliveries/{id}/pickup`, `/complete` · customer: `GET /deliveries/by-order/{orderId}`, `GET /deliveries/{id}/track` (SSE) |
| Notification | `GET /notifications` · `PATCH /notifications/{id}/read` · `GET/PUT /notifications/preferences` · `GET /notifications/stream` (SSE) |

## 7. Frontend

**Stack**

* React 19, TypeScript and Vite, in a pnpm workspace.
* React Router for routing.
* TanStack Query for server state, Zustand for UI state.
* React Hook Form with Zod for forms.
* API client generated from the OpenAPI specs.
* SSE for live order and delivery updates; Leaflet or Mapbox for maps.

| App | Modules |
|---|---|
| customer-web | Auth · Discovery (geo + filters) · Menu & customization · Cart · Checkout · Live tracking · Order history & reorder · Profile & addresses · Notifications |
| partner-portal | Onboarding · Menu management · Live order queue · Hours & availability · Reports |
| courier-pwa | Online/offline · Assignment offers · Navigation · Location broadcast · Earnings |
| admin-console | Users · Restaurant approval · Refunds · DLT replay · Health links |

**Customer workflows**

1. **Signup and login:** register → verify email → log in. The access token is held in memory;
   the refresh cookie renews it silently.
2. **Browse:** set address → nearby restaurants → menu → customize → add to cart.
   Adding from a different restaurant prompts "replace cart".
3. **Checkout:** cart review → server-side price re-validation → address → Stripe payment element
   → `POST /orders` with an Idempotency-Key.
4. **Track:** SSE status timeline plus a live courier map.
5. **After delivery:** rate, request a refund, reorder.

## 8. Testing strategy

| Level | Tooling | Gate |
|---|---|---|
| Unit (`*Test`) | JUnit 6, AssertJ, Mockito | ≥ 80% line coverage on domain/application (JaCoCo) |
| Slice | `@WebMvcTest`, `@DataJpaTest`, `@DataMongoTest`, `@JsonTest` | Controllers incl. security rules, repositories |
| Integration (`*IT`) | Testcontainers 2 + `@ServiceConnection` | Real Postgres/Mongo/Redis/Kafka; outbox, idempotency, migrations |
| Contract | Pact / Spring Cloud Contract; registry compatibility checks | No breaking API/event changes merge |
| Architecture | ArchUnit | Layering, no cycles |
| Security | CodeQL, gitleaks, dependency scanning, image scanning | PR gate |
| Frontend | Vitest, React Testing Library, MSW | PR gate |
| E2E | Playwright against docker-compose stack | signup → order → delivered |
| Performance | k6 | Checkout and search SLOs before production |

## 9. Observability (ADR-0009)

* **Metrics:** Micrometer, exported via OTLP to Prometheus.
  * RED metrics per endpoint.
  * Kafka consumer lag and outbox backlog.
  * Hikari and JVM metrics.
  * Business KPIs: orders/min, payment failure rate, time to accept, time to deliver.
* **Tracing:** OpenTelemetry to Tempo. W3C `traceparent` is propagated over HTTP **and Kafka headers**.
* **Logs:** Spring Boot structured JSON (ECS) shipped to Loki. Every entry carries
  `traceId`, `correlationId` and `userId`. PII is masked.
* **Health:** Actuator liveness/readiness groups mapped to Kubernetes probes.
* **SLOs:** checkout p99 < 800 ms, availability 99.9%, saga completion > 99.5%.
  Alerts go through Alertmanager.
* **Local:** the `grafana/otel-lgtm` all-in-one container (Grafana on :3000).

## 10. Deployment (ADR-0010)

| Concern | AWS |
|---|---|
| Compute | EKS, one namespace per environment, HPA, Karpenter |
| PostgreSQL | RDS for PostgreSQL Multi-AZ (Payment on a dedicated instance) |
| MongoDB | MongoDB Atlas on AWS (PrivateLink) |
| Redis | ElastiCache |
| Kafka | Amazon MSK + Apicurio Registry (persistent storage) |
| Edge | CloudFront + WAF + ALB; SPAs on S3 + CloudFront |
| Secrets | Secrets Manager + KMS via External Secrets Operator |
| Images | GHCR (Cloud Native Buildpacks, non-root) |
| IaC | Terraform (`infra/terraform`), remote state |
| CD | GitHub Actions with OIDC to AWS. Dev deploys automatically, staging on merge to main, prod after manual approval. Canary for order and payment. |

Database migrations follow expand/contract so old and new pods can run concurrently.

## 11. Platform baseline (verified 2026-11-07)

| Component | Version | Source |
|---|---|---|
| Java | 21 (Microsoft OpenJDK 21.0.11 locally) | — |
| Maven | 3.10.0 via wrapper (`backend/mvnw`) | — |
| Spring Boot | **4.1.2** (latest 4.1.x patch) | Maven Central |
| Spring Framework / Security | 7.x | Boot-managed |
| Spring Cloud | **2025.1.4** (supports Boot ≥4.0.0 <4.2.0-M1) | start.spring.io |
| Hibernate ORM | 7.4.13 | Boot-managed |
| Jackson | 3.1.7 (`tools.jackson.*`) | Boot-managed |
| Kafka clients | 4.2.2 (broker 4.3.2 locally, KRaft) | Boot-managed |
| Flyway | 12.4.0 | Boot-managed |
| PostgreSQL JDBC | 42.7.14 (server 17 + PostGIS 3.7) | Boot-managed |
| MongoDB driver | 5.8.1 (server 8.0) | Boot-managed |
| JUnit Jupiter | 6.0.3 | Boot-managed |
| Testcontainers | 2.0.5 | Boot-managed |
| Micrometer Tracing / OpenTelemetry | 1.7.2 / 1.62.0 | Boot-managed |
| Lombok | 1.18.48 | Boot-managed |
| springdoc-openapi | 3.2.0 (built on Boot 4.1.2) | `foodflow-parent` |
| Resilience4j | 2.4.0 (`resilience4j-spring-boot4`) | `foodflow-parent` |
| Avro / Apicurio serde | 1.12.2 / 3.3.4 | `foodflow-parent` |
| ArchUnit | 1.5.1 | `foodflow-parent` |
| JaCoCo | 0.8.16 | `foodflow-parent` |

Watch item: Spring Boot 4.2 / Spring Cloud 2026.0 are at RC stage. Upgrade them together after
GA, never Boot alone.

## 12. Roadmap

| Phase | Scope | Exit criteria |
|---|---|---|
| **0. Foundation** ✅ | Monorepo, `foodflow-parent`, docker-compose infra + observability, reusable CI, ADRs | Build green; compose stack defined |
| **1. Identity + Gateway** ✅ (code complete; Testcontainers ITs await first CI run) | User Service auth/profile/addresses, JWKS, outbox; `foodflow-platform-starter`; API Gateway; customer auth UI | Auth end-to-end through gateway; ITs green |
| **2. Catalog** | Restaurant (PostGIS) + Menu (Mongo); partner onboarding & menu UI; customer discovery | Browse nearby restaurants & menus |
| **3. Cart** | Cart (Redis), price check, menu-event invalidation; cart UI | Persistent cart; stale prices flagged |
| **4. Order + Payment** | Saga, Stripe test mode, webhooks, idempotency, DLT; partner order queue | Happy + compensation paths E2E; no double charge |
| **5. Delivery + Notification** | Courier assignment, live tracking (SSE), notifications; courier PWA | Live tracking E2E |
| **6. Hardening** | Security review, k6 load, SLO dashboards/alerts, chaos tests | SLOs met under target load |
| **7. Cloud rollout** | Terraform, EKS, managed data, Helm, CD with promotion (dev env from Phase 1) | Staging matches production; go-live checklist signed off |

## 13. Phase 1 implementation notes

Decisions made while implementing Phase 1. They refine, and do not change, the ADRs.

**Platform libraries** (`backend/libs`)

| Library | Contents | Used by |
|---|---|---|
| `foodflow-event-contracts` | Avro schemas (`EventMetadata`, `UserRegistered`), generated classes, `Topics`, `AvroClassTrust` (Avro 1.12 deserialization allow-list limited to `com.foodflow.events.*`) | producers and consumers |
| `foodflow-platform-core` | `UnresolvedPlaceholderGuard` (startup fails if configuration references an undefined variable, because Boot otherwise keeps `${VAR}` literally), `JwtValidation` (issuer + audience), `CorrelationId` rules | all services and the gateway |
| `foodflow-platform-starter` | Servlet auto-configuration: RFC 9457 error handling with `DomainException`/`ErrorCategory`, correlation-id filter, JWT resource-server building blocks, transactional outbox writer and relay | servlet services |

**Transactional outbox relay**
* Payloads are stored as Avro single-object encoding in `BYTEA`, together with the writer schema
  (`payload_schema`). Rows written by an older service version therefore still decode after a
  BACKWARD-compatible schema change during rolling deploys.
* Serialization through the registry happens at publish time, so a registry outage never blocks
  business transactions.
* A PostgreSQL advisory lock makes exactly one replica publish at a time, in `seq` order.
* Sending stops at the first failure, so no later row reaches Kafka ahead of an earlier one.
  * Acknowledgements are awaited within `send-timeout` (15 s), which exceeds the producer's
    `delivery.timeout.ms` (12 s).
  * Exponential backoff runs up to 30 s.
* Rows that can never be decoded are **parked** (`parked_at`) instead of blocking the queue.
  Registry and broker failures are retried, because they are usually transient.
* Metrics to alert on: `foodflow.outbox.pending`, `foodflow.outbox.parked` (> 0) and
  `foodflow.outbox.publish.failures`.
* Headers: `ff-event-id`, `ff-event-type`, `ff-correlation-id`, `traceparent`.
* Apicurio uses `RecordIdStrategy`, because one topic carries several event types.
* The schema is resolved **by content**. `find-latest` must stay off: with it, the serializer
  writes using the registry's latest schema rather than the record's own (verified in the serde code).

**Authentication details**
* Registration signs the user in immediately (201 plus a session).
* Self-service account types: CUSTOMER, RESTAURANT_OWNER, COURIER. ADMIN is provisioned by operations.
* **Password hashing never runs inside a transaction.** It goes through a bulkhead
  (`foodflow.auth.password-hashing`: default one slot per CPU, 2 s wait, then `503 AUTHENTICATION_BUSY`).
* **Sign-in flow:**
  1. Read a credentials snapshot.
  2. Verify the password with no connection held.
  3. Record the outcome in a short transaction that holds the user's row lock.
  * Concurrent wrong guesses are therefore each counted, and the lockout cannot be outrun.
    Verified on PostgreSQL: 12 parallel guesses give exactly four 401s, then 423.
  * Lock and suspension are re-checked under that lock before a session is issued.
* An unknown e-mail costs one dummy hash verification. Suspension (403) is revealed only after a
  correct password.
* **Refresh:**
  * Rotation, reuse revocation and sign-out of a family are serialized with a transaction-scoped
    advisory lock per family. A revocation can no longer miss a successor token being committed
    concurrently.
  * Replaying a consumed token within `refresh-reuse-grace` (10 s) is rejected **without**
    revoking the family. This absorbs benign races between tabs; outside the window, a replay
    revokes the whole family.
* The refresh cookie is `ff_refresh`: HttpOnly, Secure, SameSite=Strict, `Path=/api/v1/auth`.
  It is never placed in a response body.
* CSRF protection is disabled on purpose: the API is stateless, and the cookie endpoints rely on
  SameSite=Strict plus the CORS allow-list.
* OpenAPI and Swagger UI are **off by default** (`OPENAPI_ENABLED`); they are enabled in the local
  profile and in tests.

**Gateway**
* Reactive Spring Cloud Gateway 5.0.4 with Java route definitions.
* Every route enforces `max-request-size` (256 KB, 413 above).
* Redis token buckets:
  * sign-in/sign-up: per client IP, 1/s with a burst of 10;
  * refresh/sign-out: per client IP, 10/s with a burst of 30, so users behind a shared NAT are
    not logged out;
  * API: per user `sub`, 20/s with a burst of 40.
* **Client IP:** `X-Forwarded-For` is honoured only when the TCP peer matches `trusted-proxies`.
  The client is the entry `client-ip.trusted-hops` positions from the right (ALB = 1;
  CloudFront + ALB = 2). Direct clients cannot spoof their bucket key.
* `/internal/**` is denied. CORS uses exact origins with credentials; wildcards are rejected at startup.

**Customer web**
* React 19 with TypeScript 6.0 and Vite 8.
* The access token is kept in memory only. The session is restored from the refresh cookie on load.
* The HTTP client refreshes once on a 401 and retries.
  * Refreshes are single-flight within a tab and serialized across tabs with the Web Locks API.
  * Only a 401 ends the session; 429, 5xx and network errors do not.
* The query cache is cleared whenever the session status changes, so a user never sees the
  previous user's data.
* Production source maps are generated as `hidden`.
* The Vite dev server proxies `/api` to the gateway, so development runs on a single origin.
* pnpm 12 runs with supply-chain policies: minimum release age, and dependency build scripts
  denied unless explicitly allowed.

**CI**
* Integration tests skip themselves without Docker (developer laptops). The reusable pipeline
  **fails** if any were skipped.
* Path filters include the Maven wrapper and root POM.
* Runs on `main` are never cancelled mid-flight.

**Accepted risks (reviewed in the Phase 1 production-readiness review)**

| Risk | Why accepted / mitigation |
|---|---|
| Registered e-mails can be discovered (register returns 409; a locked account returns 423) | Removing this needs verify-by-email sign-up (Phase 5). Gateway rate limits slow probing. |
| The Redis rate limiter fails **open** if Redis is unavailable | Spring Cloud Gateway behaviour. Alert on Redis errors. Run ElastiCache Multi-AZ with auth/TLS (`REDIS_PASSWORD`, `REDIS_SSL_ENABLED`). |
| JWKS fetched over plain HTTP inside the cluster | Cluster-internal traffic only. mTLS via service mesh is planned (Phase 6/7). |
| Access tokens stay valid ≤ 15 min after logout or suspension | Short TTL. Every refresh re-checks status. The `jti` denylist is scheduled. |
| A permanently unserializable event (schema not registered) blocks its outbox | No silent loss or reordering. Alert on pending/failures. Register schemas from CI. |

**Known gaps, scheduled**

| Gap | Phase |
|---|---|
| Access-token denylist (`jti`) for immediate revocation | 6 |
| E-mail verification and password reset (removes e-mail enumeration) | 5 (needs notification-service) |
| Registering schemas from CI (auto-register is local/test only); operator tooling to replay parked outbox rows | 2 |
| `ETag`/`If-Match` on profile and address updates | 2 |
| Purging expired refresh tokens (housekeeping job) | 2 |
| Generated TypeScript client from `docs/api/*.openapi.json`; shared `packages/*` once a second app exists | 2 |
| Pin GitHub Actions to commit SHAs; CodeQL, dependency and image scanning | 6 |
| Readiness probe including database health (decide on cascading-failure policy) | 7 |
