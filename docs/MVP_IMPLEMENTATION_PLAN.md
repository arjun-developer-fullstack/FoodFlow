# FoodFlow — Fastest Path to a Working MVP

| | |
|---|---|
| **Date** | 2026-11-10 |
| **Status** | PROPOSED: awaiting approval. Nothing in this plan has been implemented. |
| **Inputs** | `docs/PROJECT_STATUS_REPORT.md` (2026-11-10), current source code, and a local start attempt of both packaged JARs (see Task 1). |
| **MVP flow** | Register/login → discover restaurants → browse menu → cart → checkout → order created → test payment → restaurant accepts → order completed → customer sees order history and status |

---

## 0. Key decisions (read first)

| # | Decision | Why | Trade-off accepted |
|---|---|---|---|
| D1 | **One new service, `commerce-service` (:8082).** It is a modular monolith with internal modules `restaurant`, `menu`, `cart`, `order`, `payment`, owning one PostgreSQL database, `commerce_db`. user-service and api-gateway stay as they are. | Checkout, order creation, payment state and restaurant acceptance all touch the same data. In one database they are **local ACID transactions** instead of a distributed saga. This is the biggest single saving in time and in consistency risk, and it does not weaken integrity. | Deviates from ADR-0001 (service granularity), ADR-0006 (saga) and ADR-0008 (Mongo menu, Redis cart). Recorded in a new **ADR-0011**. Module boundaries are enforced by ArchUnit so modules can be extracted later. |
| D2 | **Menu and cart in PostgreSQL**, not MongoDB or Redis. | Checkout locks the cart, re-prices it from the menu and creates the order in **one transaction**. Redis is still used by the gateway (rate limiting). | No cart TTL eviction from Redis. A housekeeping job deletes carts older than 7 days instead. |
| D3 | **No PostGIS or geo search in the MVP.** Discovery filters by city, cuisine and text search. Checkout requires the delivery address city to equal the restaurant city. | user-service addresses have optional coordinates and no geocoding exists, so a radius search would be fiction. | "Near me" is deferred. |
| D4 | **Payments behind a `PaymentProvider` port**, with two adapters: `StripePaymentProvider` (test mode) and `FakePaymentProvider` (local and CI only, guarded). | Satisfies "provider interface". CI and E2E tests run without Stripe secrets. Stripe can be plugged in when the account is available. | The fake adapter must never be enabled in production. A startup guard enforces this (see §4.6). |
| D5 | **Authorize at checkout, capture on restaurant acceptance** (Stripe `capture_method=manual`). Void on rejection, timeout or cancellation. | Keeps ADR-0006's rule that customers are never charged for rejected orders. | Authorizations expire after about 7 days at Stripe. MVP orders resolve within minutes. |
| D6 | **Provider calls never run inside a DB transaction.** State changes and a pending `payment_operations` row commit together. The provider call is executed right after commit and retried by a worker. | This is a **command outbox** for the payment provider: it avoids dual writes and long transactions, and gives safe retries using provider idempotency keys. | Slightly more code than calling Stripe inline. |
| D7 | **Restaurants start as `PENDING_APPROVAL`.** An admin approves them. An admin can be bootstrapped from configuration. | Closes the audit finding that anyone can self-register as `RESTAURANT_OWNER`. The role alone now grants nothing beyond creating a pending restaurant. | Adds one small admin page. |
| D8 | **Order status by polling** (every 5 s on active orders). No SSE, WebSockets, notifications or courier tracking. | Deferred by requirement. "Completion" means the restaurant marks the order completed (it handles hand-over itself). | — |
| D9 | **Orders publish `OrderStatusChanged` through the existing transactional outbox** to the existing topic `foodflow.order.events.v1`. There are no consumers in the MVP. | Preserves the outbox architecture. Later notifications and delivery services consume this event without changes to order code. Ordering keeps working if Kafka is down, because the relay backs off. | About 1 day. This is the only item that can be cut without a design change (milestone M7). |
| D10 | **Money is a `BIGINT` in minor units plus an ISO-4217 `CHAR(3)` currency.** One currency per deployment (`COMMERCE_CURRENCY`). Prices are tax-inclusive. Each restaurant has a flat delivery fee. | Avoids floating-point errors and multi-currency complexity. | Tax engine, promotions and tips are deferred. |
| D11 | **All catalog and order APIs require login.** | The MVP flow starts with login. The gateway already authenticates `/api/**`. | Anonymous browsing is deferred (a small change later). |

**Open items that need your input before or during milestone M6:**
* Does a Stripe account exist, and which country and currency is it in?
* Are the default timeouts acceptable: unpaid order expiry 30 min, restaurant acceptance 10 min?

The plan works with the fake provider until Stripe is available.

---

## TASK 1: Minimum work to run user-service and api-gateway locally

### 1.1 Verified findings

| # | Finding | How verified |
|---|---|---|
| L1 | **Docker is not installed** (`docker: command not found`). Compose, PostgreSQL, Kafka, Redis and all 17 Testcontainers ITs depend on it. | Shell check during the audit |
| L2 | **Defect: the api-gateway `local` profile cannot start.** It fails with *"Missing configuration … `${CORS_ALLOWED_ORIGINS}`, `${USER_SERVICE_URL}`, `${JWT_ISSUER}`, `${JWT_JWK_SET_URI:${USER_SERVICE_URL}/…}`, `${REDIS_HOST}`"*. | Ran `java -jar api-gateway-0.0.1-SNAPSHOT.jar --spring.profiles.active=local` |
| L3 | **Cause:** `UnresolvedPlaceholderGuard` checks **every** property source independently, including the base `application.yaml`. A profile that overrides `foodflow.gateway.routes.user-service` does not help, because the base file still contains `${USER_SERVICE_URL}`. The test profile works only because `application-test.yaml` defines the *variables themselves* (`USER_DB_URL: …`). | Code: `libs/foodflow-platform-core/.../UnresolvedPlaceholderGuard.java`. Comment in `user-service/src/test/resources/application-test.yaml`. |
| L4 | **The same defect almost certainly affects user-service `local`.** Its local profile sets `spring.datasource.url` and similar keys but never defines `USER_DB_URL`, `USER_DB_USERNAME`, `KAFKA_BOOTSTRAP_SERVERS`, `SCHEMA_REGISTRY_URL` or `JWT_ISSUER`. The start attempt failed earlier, on the database connection, before the guard ran, so this is **inferred, not yet observed**. | Code inspection plus the start attempt |
| L5 | `infra/docker/.env` does not exist. It must be created from `.env.example`. | `ls infra/docker` |
| L6 | Kafka topics, the Apicurio auto-register setting (local), the `user_db` role and database, and the issuer (`http://localhost:8080`) are already consistent between compose, user-service and the gateway. | Config inspection |

### 1.2 Minimum steps (milestone M0)

1. **Install a Docker engine.** Use Docker Desktop, or Rancher Desktop in *dockerd (moby)* mode if a Docker Desktop licence is a problem. Testcontainers works with both.
2. `cp infra/docker/.env.example infra/docker/.env` and replace every `change-me` with a random value.
3. **Fix L2 and L4 (configuration only, no Java changes):**
   * `backend/api-gateway/src/main/resources/application-local.yaml`: define `USER_SERVICE_URL: http://localhost:8081`, `CORS_ALLOWED_ORIGINS: http://localhost:5173`, `JWT_ISSUER: http://localhost:8080`, `REDIS_HOST: localhost` as top-level keys, the same pattern as `application-test.yaml`. Remove the now-redundant overrides.
   * `backend/user-service/src/main/resources/application-local.yaml`: define `USER_DB_URL`, `USER_DB_USERNAME`, `KAFKA_BOOTSTRAP_SERVERS`, `SCHEMA_REGISTRY_URL`, `SCHEMA_REGISTRY_AUTO_REGISTER: true`, `JWT_ISSUER`, `OPENAPI_ENABLED: true` the same way. `USER_DB_PASSWORD` still comes from `.env`.
   * Add a regression test per service: `LocalProfileConfigurationTest`. It boots only the environment with the `local` profile plus a dummy `.env` value and asserts that the guard passes.
4. `cd infra/docker && docker compose up -d`, then confirm that all containers are healthy and `kafka-init` lists 7 topics.
5. `cd backend && ./mvnw verify`. **Expect 17/17 ITs to pass with 0 skipped.** Fix anything they reveal. These tests have never run, so budget time for this.
6. Start user-service and the gateway with `-Dspring-boot.run.profiles=local`. Run `pnpm install && pnpm dev`, then manually register, log in, reload the page (silent refresh) and log out through `http://localhost:5173`.
7. **Hygiene and first commit** (needs your explicit approval when it happens):
   * remove the Initializr duplicates in `backend/user-service` (`mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `.gitattributes`)
   * create a GitHub remote, push, and confirm that the 3 workflows are green, including the "no skipped ITs" gate

**Effort:** 1–2 days. Most of this is the first real IT run.
**Definition of Done:**
* Both services start with `local`.
* 17/17 ITs pass locally and in CI.
* The manual auth journey works through the gateway.
* README local steps are accurate.

---

## TASK 2: Smallest practical Restaurant and Menu

Both live in `commerce-service` under the packages `com.foodflow.commerce.restaurant` and `com.foodflow.commerce.menu`. Each package follows the user-service layering: `api`, `application`, `domain`, `infrastructure`.

### 2.1 Scope

| In the MVP | Deferred |
|---|---|
| Owner creates and edits a restaurant: name, description, cuisine, phone, address, city, delivery fee, minimum order | Opening-hours schedules (replaced by an **`acceptingOrders` toggle**) |
| Admin approves or suspends a restaurant | Geo/radius search, PostGIS |
| Discovery: active restaurants filtered by `city`, `cuisine`, `q` (name prefix or contains), paginated | Ratings, images upload (only an `imageUrl` text field, optional, validated as `https`) |
| Menu: categories (ordered) and items (name, description, price, available, position) | Variants, add-ons, modifiers, allergens |
| Item availability toggle; item archiving (soft delete, so order history stays valid) | Menu versioning and scheduling |

### 2.2 Tables (Flyway, `commerce_db`)

**`V1__restaurants.sql`**

```sql
CREATE TABLE restaurants (
  id UUID PRIMARY KEY,
  owner_user_id UUID NOT NULL,                       -- JWT sub of a RESTAURANT_OWNER (user-service id)
  name VARCHAR(120) NOT NULL,
  description VARCHAR(1000),
  cuisine VARCHAR(50) NOT NULL,
  phone VARCHAR(20),
  line1 VARCHAR(200) NOT NULL, line2 VARCHAR(200),
  city VARCHAR(100) NOT NULL, city_normalized VARCHAR(100) NOT NULL,   -- lower(trim(city))
  postal_code VARCHAR(20) NOT NULL, country CHAR(2) NOT NULL,
  image_url VARCHAR(500),
  currency CHAR(3) NOT NULL,
  delivery_fee_minor BIGINT NOT NULL CHECK (delivery_fee_minor >= 0),
  min_order_minor BIGINT NOT NULL DEFAULT 0 CHECK (min_order_minor >= 0),
  status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING_APPROVAL','ACTIVE','SUSPENDED')),
  accepting_orders BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL
);
CREATE INDEX ix_restaurants_discovery ON restaurants (city_normalized, cuisine) WHERE status = 'ACTIVE';
CREATE INDEX ix_restaurants_owner ON restaurants (owner_user_id);
CREATE INDEX ix_restaurants_status_created ON restaurants (status, created_at);
```

**`V2__menu.sql`**

```sql
CREATE TABLE menu_categories (
  id UUID PRIMARY KEY,
  restaurant_id UUID NOT NULL REFERENCES restaurants(id),
  name VARCHAR(80) NOT NULL, position INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL,
  CONSTRAINT ux_menu_categories_name UNIQUE (restaurant_id, name)
);
CREATE TABLE menu_items (
  id UUID PRIMARY KEY,
  restaurant_id UUID NOT NULL REFERENCES restaurants(id),
  category_id UUID NOT NULL REFERENCES menu_categories(id),
  name VARCHAR(120) NOT NULL, description VARCHAR(500),
  price_minor BIGINT NOT NULL CHECK (price_minor > 0),
  currency CHAR(3) NOT NULL,
  available BOOLEAN NOT NULL DEFAULT TRUE,
  position INT NOT NULL DEFAULT 0,
  archived_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL
);
CREATE INDEX ix_menu_items_restaurant ON menu_items (restaurant_id, category_id, position) WHERE archived_at IS NULL;
```

Restaurant and menu rows are owned by `commerce_db`. There is no cross-database reference to `users`: `owner_user_id` is a trusted JWT `sub`.

### 2.3 Endpoints

Paths are listed without the `/api/v1` prefix. Every endpoint requires authentication. Errors are RFC 9457 problems from the platform starter.

| Method & path | Role | Behaviour |
|---|---|---|
| `GET /restaurants?city&cuisine&q&page&size` | any authenticated | Only `ACTIVE` restaurants. Returns `PageResponse` with summaries, including `acceptingOrders`. |
| `GET /restaurants/{id}` | any | 404 unless `ACTIVE` (owners and admins use their own endpoints) |
| `GET /restaurants/{id}/menu` | any | Categories with non-archived items, including `available` |
| `POST /partner/restaurants` | `RESTAURANT_OWNER` | Creates a `PENDING_APPROVAL` restaurant. Maximum 5 per owner. |
| `GET /partner/restaurants` | owner | The caller's restaurants, any status |
| `GET /partner/restaurants/{id}` and `PUT /partner/restaurants/{id}` | owner of that restaurant | 404 if not the owner |
| `PATCH /partner/restaurants/{id}/accepting-orders` `{acceptingOrders}` | owner | 422 `RESTAURANT_NOT_ACTIVE` unless `ACTIVE` |
| `GET /partner/restaurants/{id}/menu` | owner | Includes unavailable items (not archived) |
| `POST`, `PUT`, `DELETE` `/partner/restaurants/{id}/menu/categories[/{cid}]` | owner | Delete returns 409 `CATEGORY_NOT_EMPTY` if it still has items |
| `POST`, `PUT`, `DELETE` `/partner/restaurants/{id}/menu/items[/{itemId}]` | owner | Delete means archive |
| `PATCH /partner/restaurants/{id}/menu/items/{itemId}/availability` | owner | — |
| `GET /admin/restaurants?status&page&size` | `ADMIN` | — |
| `PATCH /admin/restaurants/{id}/status` `{status: ACTIVE or SUSPENDED}` | `ADMIN` | Suspending sets `accepting_orders=false`. Open orders are unaffected. |

**Ownership rule:** every `/partner/**` query includes `owner_user_id = :sub` in the SQL. An unknown or foreign id returns **404**, never 403, so ids cannot be discovered.

### 2.4 Tests

* **Unit:** `Restaurant` state rules (approve, suspend, accepting toggle only when `ACTIVE`); `MenuItem` price, currency and archive rules.
* **Web slice:** each controller, covering role rules (customer calling `/partner/**` gets 403; owner calling `/admin/**` gets 403), validation 400s, and 404 for a foreign restaurant.
* **Integration (`RestaurantMenuIT`):**
  * owner creates a restaurant; it is not discoverable while pending
  * admin approves it; it becomes discoverable by city and cuisine
  * a menu item set unavailable shows `available=false`
  * an archived item disappears
  * an owner B request for owner A's restaurant returns 404

---

## TASK 3: Cart, Checkout and Order

### 3.1 Cart (`com.foodflow.commerce.cart`)

**`V3__cart.sql`**

```sql
CREATE TABLE carts (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL UNIQUE,
  restaurant_id UUID NOT NULL REFERENCES restaurants(id),
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL
);
CREATE TABLE cart_items (
  cart_id UUID NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
  menu_item_id UUID NOT NULL REFERENCES menu_items(id),
  quantity INT NOT NULL CHECK (quantity BETWEEN 1 AND 50),
  added_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (cart_id, menu_item_id)
);
CREATE INDEX ix_carts_updated_at ON carts (updated_at);   -- 7-day housekeeping purge
```

| Endpoint (role `CUSTOMER`) | Behaviour |
|---|---|
| `GET /cart` | Returns the cart **priced live from the menu**: line name, unit price, line total, `available` flag, subtotal, delivery fee, total and `checkoutAllowed`, plus a list of `problems` (`ITEM_UNAVAILABLE`, `RESTAURANT_NOT_ACCEPTING`, `BELOW_MINIMUM`). An empty cart returns `200` with `items: []`. |
| `PUT /cart/items/{menuItemId}` `{quantity}` | **Idempotent set.** The item must be active and available, and its restaurant must be `ACTIVE`. If the cart belongs to another restaurant: **409 `CART_RESTAURANT_MISMATCH`**, unless `?replaceCart=true`. Maximum 30 distinct lines. |
| `DELETE /cart/items/{menuItemId}` | Idempotent |
| `DELETE /cart` | Idempotent |

The cart stores **no prices**. Prices are always read from the menu, so a cart can never hold a stale price. Checkout re-validates anyway (§3.3).

### 3.2 Orders (`com.foodflow.commerce.order`)

**`V4__orders.sql`**

```sql
CREATE TABLE orders (
  id UUID PRIMARY KEY,
  customer_user_id UUID NOT NULL,
  restaurant_id UUID NOT NULL REFERENCES restaurants(id),
  status VARCHAR(24) NOT NULL CHECK (status IN
    ('PENDING_PAYMENT','PLACED','ACCEPTED','READY','COMPLETED','REJECTED','CANCELLED','EXPIRED')),
  currency CHAR(3) NOT NULL,
  subtotal_minor BIGINT NOT NULL CHECK (subtotal_minor > 0),
  delivery_fee_minor BIGINT NOT NULL CHECK (delivery_fee_minor >= 0),
  total_minor BIGINT NOT NULL CHECK (total_minor = subtotal_minor + delivery_fee_minor),
  -- delivery address snapshot (customer-provided at checkout)
  addr_label VARCHAR(50), addr_line1 VARCHAR(200) NOT NULL, addr_line2 VARCHAR(200),
  addr_city VARCHAR(100) NOT NULL, addr_postal_code VARCHAR(20) NOT NULL, addr_country CHAR(2) NOT NULL,
  customer_note VARCHAR(300),
  cancel_reason VARCHAR(40),
  payment_deadline_at TIMESTAMPTZ NOT NULL,       -- PENDING_PAYMENT → EXPIRED
  acceptance_deadline_at TIMESTAMPTZ,             -- PLACED → REJECTED (timeout)
  placed_at TIMESTAMPTZ, accepted_at TIMESTAMPTZ, ready_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ, closed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL
);
CREATE INDEX ix_orders_customer ON orders (customer_user_id, created_at DESC);
CREATE INDEX ix_orders_restaurant_status ON orders (restaurant_id, status, created_at);
CREATE INDEX ix_orders_payment_deadline ON orders (payment_deadline_at) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX ix_orders_acceptance_deadline ON orders (acceptance_deadline_at) WHERE status = 'PLACED';

CREATE TABLE order_items (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  menu_item_id UUID NOT NULL,                     -- reference only; name/price are snapshotted
  name VARCHAR(120) NOT NULL,
  unit_price_minor BIGINT NOT NULL CHECK (unit_price_minor > 0),
  quantity INT NOT NULL CHECK (quantity BETWEEN 1 AND 50),
  line_total_minor BIGINT NOT NULL CHECK (line_total_minor = unit_price_minor * quantity)
);
CREATE INDEX ix_order_items_order ON order_items (order_id);

CREATE TABLE order_status_history (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  from_status VARCHAR(24), to_status VARCHAR(24) NOT NULL,
  actor VARCHAR(20) NOT NULL CHECK (actor IN ('CUSTOMER','RESTAURANT','SYSTEM','PAYMENT_PROVIDER')),
  actor_user_id UUID, reason VARCHAR(40),
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_order_status_history_order ON order_status_history (order_id, occurred_at);

CREATE TABLE checkout_idempotency_keys (
  user_id UUID NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL,                 -- SHA-256 of the canonical request body
  order_id UUID NOT NULL REFERENCES orders(id),
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, idempotency_key)
);
CREATE INDEX ix_checkout_idempotency_created ON checkout_idempotency_keys (created_at);  -- 24h purge
```

**State machine.** It is implemented once, in `Order.transitionTo(...)`, which also writes history and the event.

```
PENDING_PAYMENT ──payment authorized──► PLACED ──restaurant accepts──► ACCEPTED ──ready──► READY ──complete──► COMPLETED
      │                                   │  ├─ restaurant rejects ─► REJECTED      (void authorization)
      │                                   │  └─ acceptance timeout ─► REJECTED      (void, reason TIMEOUT)
      │                                   └──── customer cancels ──► CANCELLED      (void)
      ├── customer cancels ─► CANCELLED (cancel intent if any)
      └── payment deadline ─► EXPIRED   (cancel intent if any)
ACCEPTED ── capture fails permanently ─► CANCELLED (reason PAYMENT_CAPTURE_FAILED; ops alert)
```

Rules:
* `COMPLETED` requires `payment.status = CAPTURED`. An unpaid order can never complete.
* No cancellation after `ACCEPTED` in the MVP. Refunds go through the Stripe dashboard (deferred).

### 3.3 Checkout: `POST /orders` (role `CUSTOMER`)

**Request**
* Header: `Idempotency-Key: <8–64 chars [A-Za-z0-9-]>`. Required. A missing key returns 400 `IDEMPOTENCY_KEY_REQUIRED`.
* Body: `{ deliveryAddress{label?, line1, line2?, city, postalCode, country}, customerNote?, expectedTotalMinor }`.

**One transaction, `READ COMMITTED`, short.** No network calls happen inside it.

1. Look up `(user_id, key)` in `checkout_idempotency_keys`. If found and the request hash matches, return **200** with the existing order. If the hash differs, return **422 `IDEMPOTENCY_KEY_REUSED`**.
2. `SELECT … FOR UPDATE` the caller's cart. This serializes concurrent checkouts and cart edits. Empty or missing cart returns 422 `CART_EMPTY`.
3. Load the restaurant. It must be `ACTIVE` and `accepting_orders`, otherwise 422 `RESTAURANT_NOT_ACCEPTING`.
4. **Server-side pricing:** load every cart item's menu row. Each one must belong to the cart's restaurant, not be archived, be available and match the restaurant currency. Otherwise return 422 `ITEM_UNAVAILABLE` with item ids.
5. Compute `line_total = price × qty`, `subtotal = Σ`, `total = subtotal + delivery_fee`, all with `Math.multiplyExact` and `Math.addExact`. The minimum-order check returns 422 `BELOW_MINIMUM_ORDER`.
6. If `total ≠ expectedTotalMinor`, return **409 `PRICE_CHANGED`** with the fresh priced cart. The customer must confirm the new total, so the amount charged always equals the amount shown.
7. The delivery address city must equal the restaurant city (normalized), otherwise 422 `OUTSIDE_DELIVERY_AREA`.
8. Insert the `orders` row (`PENDING_PAYMENT`, `payment_deadline_at = now + 30 min`), `order_items` snapshots, a history row, and a `payments` row (`CREATED`; see §4).
9. Insert into `checkout_idempotency_keys`. A unique violation from a concurrent duplicate rolls back; the caller re-reads and returns the winner's order with 200.
10. Delete the cart.
11. Append `OrderStatusChanged` to the outbox (milestone M7).
12. Commit, then return **201** `{order}`.

The client then calls `POST /orders/{id}/payment-session` (§4.3).

**Concurrency and integrity guarantees**

| Guarantee | Mechanism |
|---|---|
| One order per checkout attempt | `(user_id, idempotency_key)` primary key, inside the transaction |
| Concurrent cart edits never mix with checkout | Cart row lock |
| Totals cannot be tampered with | Totals come only from the server and are pinned by `CHECK` constraints in the database |
| Order transitions stay consistent | Transitions load the order `FOR UPDATE` and check the expected status (`ORDER_STATE_CONFLICT` 409 otherwise). `@Version` adds optimistic locking for edits. |

### 3.4 Order endpoints

| Method & path | Role | Notes |
|---|---|---|
| `POST /orders` | CUSTOMER | Checkout (above) |
| `GET /orders?page&size` | CUSTOMER | Own orders, newest first (history) |
| `GET /orders/{id}` | CUSTOMER (own) | Order, items, status, timestamps, `payment.status`, `history[]`. 404 if not own. |
| `POST /orders/{id}/cancel` | CUSTOMER (own) | Only `PENDING_PAYMENT` or `PLACED`. Otherwise 409 `ORDER_STATE_CONFLICT`. |
| `GET /partner/restaurants/{rid}/orders?status=PLACED,ACCEPTED,READY&page&size` | owner | Order queue. `PENDING_PAYMENT` is never shown. |
| `POST /partner/orders/{id}/accept` | owner of the order's restaurant | `PLACED` → `ACCEPTED` and enqueues `CAPTURE`. Repeating on `ACCEPTED` is a 200 no-op. |
| `POST /partner/orders/{id}/reject` `{reason}` | owner | `PLACED` → `REJECTED` and enqueues `CANCEL` |
| `POST /partner/orders/{id}/ready` | owner | `ACCEPTED` → `READY` |
| `POST /partner/orders/{id}/complete` | owner | `READY` → `COMPLETED`. Requires the payment to be `CAPTURED` (otherwise 409 `PAYMENT_NOT_CAPTURED`; the UI shows "payment pending"). |

**Scheduled jobs.** They run in the `order` module. They are multi-replica safe because they use `SELECT … FOR UPDATE SKIP LOCKED LIMIT 50`.
* `PaymentDeadlineJob` (every 30 s): `PENDING_PAYMENT` past the deadline → `EXPIRED`, plus `CANCEL` if an intent exists.
* `AcceptanceDeadlineJob` (every 30 s): `PLACED` past the deadline → `REJECTED` (reason `TIMEOUT`), plus `CANCEL`.
* `CartHousekeepingJob` (hourly): deletes carts not updated for 7 days and idempotency keys older than 24 h.

### 3.5 Tests

* **Unit `OrderTest`:**
  * every legal transition
  * every illegal transition rejected
  * `COMPLETED` requires `CAPTURED`
  * history row on each transition
* **Unit `CheckoutServiceTest`** (in-memory repositories, as in user-service):
  * price changed returns 409
  * unavailable item
  * archived item
  * restaurant paused
  * below minimum
  * wrong city
  * empty cart
  * overflow protection
  * idempotent replay returns the same order
  * same key with a different body returns 422
* **`CheckoutConcurrencyIT`** (Testcontainers PostgreSQL):
  * 10 parallel `POST /orders` with the same key create 1 order and 1 payment
  * parallel checkout and cart edit: either the edit happens first or the cart is gone; never a half-priced order
* **`OrderLifecycleIT`:**
  * full transitions through the API
  * a customer cannot read another customer's order (404)
  * owner B cannot accept owner A's order (404)
  * deadline jobs expire and reject with the clock advanced
* **Web slice:** `Idempotency-Key` validation, role rules, problem codes.

---

## TASK 4: Payment integration and webhook lifecycle

### 4.1 Port and adapters (`com.foodflow.commerce.payment`)

```java
// application layer: no Stripe types leak out
public interface PaymentProvider {
    String name();                                            // "stripe" | "fake"
    ProviderIntent createIntent(CreateIntent cmd);            // amount, currency, orderId, idempotencyKey
    ProviderIntent retrieveIntent(String providerPaymentId);  // used by payment-session + reconciliation
    void capture(String providerPaymentId, long amountMinor, String idempotencyKey);
    void cancel(String providerPaymentId, String idempotencyKey);
    ProviderEvent verifyAndParseWebhook(byte[] rawBody, Map<String, String> headers); // throws InvalidWebhookSignature
}
record ProviderIntent(String providerPaymentId, ProviderIntentStatus status, long amountMinor,
                      String currency, String clientSecret /* never persisted, never logged */) {}
```

| Adapter | Use | Details |
|---|---|---|
| `StripePaymentProvider` | Test mode (and production later) | `stripe-java` SDK, version pinned in `foodflow-parent` and verified at implementation time. PaymentIntent with `capture_method=manual` and `automatic_payment_methods.enabled=true`. `metadata.orderId` and `metadata.paymentId`. Stripe `Idempotency-Key` = `payment_operations.id`. Webhook verification with `Webhook.constructEvent(rawBody, Stripe-Signature, STRIPE_WEBHOOK_SECRET)` and a 300 s tolerance. |
| `FakePaymentProvider` | Local development, CI, E2E | In-memory and DB-backed intents. A dev-only endpoint `POST /payments/fake/{orderId}/confirm?outcome=success or decline` produces the same `ProviderEvent`s through the **same webhook-processing code path**. |

**Configuration** (environment variables, all checked by the placeholder guard):
* `PAYMENT_PROVIDER=stripe|fake`
* `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` (stripe only)
* frontend `VITE_STRIPE_PUBLISHABLE_KEY`

### 4.2 Tables (`V5__payments.sql`)

```sql
CREATE TABLE payments (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL UNIQUE REFERENCES orders(id),
  provider VARCHAR(20) NOT NULL,
  provider_payment_id VARCHAR(100) UNIQUE,
  status VARCHAR(24) NOT NULL CHECK (status IN
    ('CREATED','AUTHORIZED','CAPTURE_PENDING','CAPTURED','CANCEL_PENDING','CANCELLED','FAILED')),
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  currency CHAR(3) NOT NULL,
  last_failure_code VARCHAR(60),
  authorized_at TIMESTAMPTZ, captured_at TIMESTAMPTZ, cancelled_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL
);

-- Command outbox for provider calls (D6)
CREATE TABLE payment_operations (
  id UUID PRIMARY KEY,                             -- also the provider idempotency key
  payment_id UUID NOT NULL REFERENCES payments(id),
  type VARCHAR(20) NOT NULL CHECK (type IN ('CREATE_INTENT','CAPTURE','CANCEL')),
  status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL,
  last_error VARCHAR(1000),
  created_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_payment_operations_one_active
  ON payment_operations (payment_id, type) WHERE status = 'PENDING';
CREATE INDEX ix_payment_operations_due ON payment_operations (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE payment_webhook_events (
  provider VARCHAR(20) NOT NULL,
  event_id VARCHAR(100) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (provider, event_id)
);
```

`V6__outbox.sql` holds the `outbox_events` table and indexes, identical to the user-service V1 section, because each service owns its schema.

### 4.3 Lifecycle

```
Customer            commerce-service                                   Stripe
   │ POST /orders ───► tx: order PENDING_PAYMENT + payment CREATED
   │ ◄── 201 order
   │ POST /orders/{id}/payment-session
   │                   ensure CREATE_INTENT op (idempotent) ─────────► create PaymentIntent (manual capture,
   │                   store provider_payment_id                         idempotency key = op id)
   │ ◄── {clientSecret, publishableKey, amount}
   │ Stripe Payment Element: confirmPayment (card data goes browser → Stripe only)
   │                                                     ◄──── webhook payment_intent.amount_capturable_updated
   │                   tx: dedupe event, verify amount/currency/orderId,
   │                       payment AUTHORIZED, order PLACED (+deadline), history, outbox
Owner POST accept ───► tx: order ACCEPTED, payment CAPTURE_PENDING, CAPTURE op
                       after commit: capture ───────────────────────► capture
                                                     ◄──── webhook payment_intent.succeeded
                       tx: payment CAPTURED
Reject/timeout/cancel► tx: order REJECTED/CANCELLED/EXPIRED, CANCEL op ──► cancel ◄── payment_intent.canceled
                       tx: payment CANCELLED
```

**`POST /orders/{id}/payment-session`** (customer, own order, `PENDING_PAYMENT` only):
* Idempotent. If no intent exists, it executes the `CREATE_INTENT` op synchronously.
* It always returns the client secret by **retrieving** the intent. The secret is never stored.
* If the provider is unavailable it returns 503 `PAYMENT_PROVIDER_UNAVAILABLE`, and the UI offers a retry.

**Webhook endpoint `POST /payments/webhooks/stripe`:**
* Public at the gateway and in the service (`permitAll`), protected by the signature. The raw body is read as `byte[]`.
* Rate-limited per IP at the gateway with the existing `session` bucket. Body limit 256 KB (existing).
* Processing, in a single transaction:
  1. Verify the signature. An invalid signature returns **400**, with no state change and a `SECURITY` log entry.
  2. `INSERT … ON CONFLICT DO NOTHING` into `payment_webhook_events`. If nothing was inserted, the event is a duplicate: return **200** immediately.
  3. Map the event and apply it to the payment and order state machines. **Transitions only move forward.** Stale or out-of-order events (for example `amount_capturable_updated` after `CANCELLED`) are recorded and ignored.
  4. If authorization arrives for an order that is already `EXPIRED` or `CANCELLED`, enqueue `CANCEL`. The money is released, never captured.
  5. **Amount, currency and `metadata.orderId` must match** the payment row. A mismatch returns 200, does not transition, and raises a `SECURITY` alert (`foodflow.payments.webhook.mismatch` counter).
* Processing failure (database down) returns 5xx so that Stripe retries. The dedupe row rolls back with the transaction.

| Stripe event | Effect |
|---|---|
| `payment_intent.amount_capturable_updated` (status `requires_capture`) | payment `AUTHORIZED`; order `PENDING_PAYMENT` → `PLACED` |
| `payment_intent.payment_failed` | `last_failure_code` recorded. The order stays `PENDING_PAYMENT` (the customer may retry with another card until the deadline). |
| `payment_intent.succeeded` | payment `CAPTURED` |
| `payment_intent.canceled` | payment `CANCELLED` |
| anything else | Recorded and ignored |

**`PaymentOperationWorker`** (every 5 s, `FOR UPDATE SKIP LOCKED`):
* Executes due `PENDING` ops with exponential backoff (max 5 min, max 10 attempts).
* Permanent provider errors mark the op `FAILED`, and the service applies compensation. A failed `CAPTURE` → order `CANCELLED` (`PAYMENT_CAPTURE_FAILED`) and payment `FAILED`.
* Metrics: `foodflow.payments.operations.pending` and `foodflow.payments.operations.failed`.

**`PaymentReconciliationJob`** (every 2 min) is the safety net for missed webhooks:
* For payments in `CREATED`, `CAPTURE_PENDING` or `CANCEL_PENDING` older than 2 min that have a `provider_payment_id`, it retrieves the intent.
* It applies the same transition code as the webhook.

**Local Stripe testing:** `stripe listen --forward-to localhost:8080/api/v1/payments/webhooks/stripe`. The CLI prints the webhook signing secret. Test cards:
* `4242 4242 4242 4242`: success
* `4000 0000 0000 9995`: decline
* `4000 0025 0000 3155`: 3-D Secure

### 4.4 Tests

* **Unit `PaymentTest`:** state machine, forward-only, amount checks.
* **Unit `WebhookProcessorTest`:**
  * duplicate event processed once
  * out-of-order events
  * mismatched amount
  * authorization after expiry triggers `CANCEL`
* **Unit `StripePaymentProviderTest`:**
  * signature verification with a generated test signature (valid, tampered body, expired timestamp, wrong secret)
  * mapping of Stripe statuses
  * idempotency key passed on every call
  * HTTP stubbed (`MockRestServiceServer`-style or a stub server), no network
* **`PaymentLifecycleIT`** (fake provider, PostgreSQL):
  * authorize → accept → capture → complete
  * reject → cancel
  * provider failure → op retried → succeeds
  * permanent capture failure → compensation
  * reconciliation fixes a missed webhook
* **`StripeWebhookIT`:** real signature computed with a test secret, posted through MockMvc, state verified, replay returns 200 with no change.
* **Manual or nightly `StripeTestModeE2E`:** opt-in when Stripe secrets are present. Not part of the PR gate.

### 4.5 Gateway changes

* `GatewayProperties.Routes`: add `@NotNull URI commerceService` (`COMMERCE_SERVICE_URL`).
* `RouteConfiguration`:
  * route `commerce-api`: `/api/v1/restaurants/**`, `/api/v1/cart/**`, `/api/v1/orders/**`, `/api/v1/partner/**`, `/api/v1/admin/restaurants/**` with the api limiter, keyed by principal
  * route `commerce-webhooks`: `/api/v1/payments/webhooks/**` with the session limiter, keyed by IP
  * route `commerce-fake-payments`: `/api/v1/payments/fake/**`, **only when `foodflow.gateway.routes.fake-payments-enabled=true`** (local and CI)
* `GatewaySecurityConfiguration`: `permitAll` for `POST /api/v1/payments/webhooks/**`. Everything else stays authenticated. `/api/v1/admin/**` is already `ADMIN`.
* Tests: extend `GatewayEdgeTest`:
  * the webhook is reachable without a token
  * `/api/v1/payments/fake/**` returns 404 when disabled
  * commerce routes require a token

### 4.6 Security controls for payments

* Card data never reaches FoodFlow (Payment Element). This matches SAQ-A, as in the architecture.
* Secrets come only from the environment. `clientSecret` is never logged or persisted. `toString()` overrides redact it, following the `AuthSession` pattern.
* **Fake provider guard:** startup fails if `PAYMENT_PROVIDER=fake` and `foodflow.payments.fake.allowed` is not `true`. Only `application-local.yaml` and `application-test.yaml` set it. The fake confirm endpoint is not registered at all unless the fake provider is active.
* The amount charged is always the server-computed `payments.amount_minor`. It is never taken from the client.

---

## TASK 5: Minimum React pages

All pages live in `frontend/apps/customer-web`. One SPA with role-based routing keeps the "no unnecessary apps" principle; a separate partner portal is deferred.

* **Roles** come from `GET /users/me` (`roles[]`).
* **`RequireRole`** guard: wrong role → 403 page.
* **Polling:** TanStack Query `refetchInterval: 5000` on active orders and the partner queue.

| Route | Role | Page | Key behaviour |
|---|---|---|---|
| `/login`, `/register` | guest | exist | Register gains an "I'm a restaurant partner" option that sends `accountType: RESTAURANT_OWNER` |
| `/` | any | Role landing | Redirects: customer → `/restaurants`, owner → `/partner`, admin → `/admin/restaurants` |
| `/restaurants` | CUSTOMER | Restaurant list | City input (remembered in `localStorage`, not sensitive), cuisine filter, search, paging, "closed" badge |
| `/restaurants/:id` | CUSTOMER | Menu | Categories and items, add/quantity controls, unavailable items disabled, "replace cart?" dialog on 409 `CART_RESTAURANT_MISMATCH` |
| `/cart` | CUSTOMER | Cart | Live-priced lines, problem banners, quantity edits, totals, "Checkout" disabled unless `checkoutAllowed` |
| `/checkout` | CUSTOMER | Checkout | Choose a saved address (user-service `GET /users/me/addresses`) or add one inline (`POST /users/me/addresses`), note, order summary. Generates the `Idempotency-Key` **once per checkout attempt** (kept in component state and `sessionStorage`, so retries and double-clicks reuse it). Handles 409 `PRICE_CHANGED` with a "confirm new total" step. |
| `/orders/:id/pay` | CUSTOMER | Payment | Stripe `PaymentElement` (`@stripe/react-stripe-js`) with the client secret from `payment-session`. In fake mode, "Pay (test)" and "Decline (test)" buttons. On success → order page. |
| `/orders/:id` | CUSTOMER | Order status | Status timeline from `history[]`, items, totals, cancel button when allowed, 5 s polling while not terminal |
| `/orders` | CUSTOMER | Order history | Paginated list with status chips |
| `/partner` | OWNER | My restaurants | List with status (pending approval, active, suspended), "Create restaurant" form |
| `/partner/restaurants/:id` | OWNER | Restaurant settings | Edit details, `acceptingOrders` toggle |
| `/partner/restaurants/:id/menu` | OWNER | Menu editor | Category CRUD, item CRUD (price input in major units, converted to minor in one helper), availability toggle |
| `/partner/restaurants/:id/orders` | OWNER | Order queue | Tabs New (`PLACED`) / Accepted / Ready, with Accept, Reject (reason), Ready and Complete buttons, 5 s polling, acceptance-deadline countdown |
| `/admin/restaurants` | ADMIN | Restaurant approvals | Pending list, Approve and Suspend buttons |

**Shared frontend work**
* `openapi-typescript`-generated **types** from `docs/api/*.openapi.json`, kept behind the existing `apiRequest`. This closes the documented gap without changing the HTTP client.
* `Money` formatting helper (`Intl.NumberFormat`, minor ↔ major units).
* Minimal UI kit: `Button`, `Card`, `Dialog`, `Badge`, `QuantityStepper`, `EmptyState`, `ErrorAlert`.
* Two responsive breakpoints: a single column on mobile and a grid for restaurant and menu lists.

**Frontend tests** (Vitest, Testing Library, MSW), at least one per page:
* rendering
* role guard
* error states (`PRICE_CHANGED`, `CART_RESTAURANT_MISMATCH`, 503 payment)
* idempotency key reuse on retry

---

## TASK 6: End-to-end acceptance tests

* **Tooling:** Playwright (`frontend/e2e/`) against the compose infrastructure plus user-service, commerce-service and the gateway (started by a script with `local` profiles and `PAYMENT_PROVIDER=fake`), and a built customer-web (`vite preview`).
* **Data setup:** a `seed` fixture uses the public APIs: register users, bootstrap an admin, create and approve a restaurant, and create a menu.
* **Runs:** in CI as a new `e2e.yml` workflow on PRs that touch `backend/**` or `frontend/**`. Stripe variants run nightly or manually when secrets exist.

| ID | Scenario | Acceptance criteria |
|---|---|---|
| **E2E-01** | **Happy path:** customer registers → finds the restaurant by city → adds 2 items → cart → checkout with a new address → pays (fake success) → order `PLACED` → owner accepts → ready → complete → customer sees `COMPLETED` in the order page and history | Every status is shown on both sides within one polling interval. Totals on cart, checkout, order and payment are equal. The payment is `CAPTURED` exactly once. |
| E2E-02 | Owner changes an item price after it is in the cart | Checkout shows the new total (409 `PRICE_CHANGED`). The order uses the new price only after confirmation. |
| E2E-03 | Owner marks an item unavailable or pauses the restaurant | The cart shows a problem banner. Checkout is blocked. |
| E2E-04 | Double-click "Place order" and a network retry (same Idempotency-Key) | Exactly 1 order and 1 payment intent (checked through the API). |
| E2E-05 | Payment declined, then retried successfully | The order stays `PENDING_PAYMENT` after the decline, then becomes `PLACED`. One payment row. |
| E2E-06 | Restaurant rejects | Order `REJECTED`, payment `CANCELLED`, no capture. Customer sees the reason. |
| E2E-07 | Restaurant does not respond (acceptance deadline shortened through test config) | Auto-`REJECTED` (`TIMEOUT`), authorization voided |
| E2E-08 | Customer abandons payment (payment deadline shortened) | `EXPIRED`. The intent is cancelled if it was created. |
| E2E-09 | Customer cancels while `PLACED` | `CANCELLED` and voided. Accept afterwards returns 409. |
| E2E-10 | Authorization matrix | Customer B cannot get, cancel or pay for A's order (404). Owner B cannot see or act on A's restaurant or orders (404). Customer calling `/partner/**` gets 403. Owner calling `/admin/**` gets 403. A pending restaurant is not discoverable and cannot receive items in a cart. |
| E2E-11 | Webhook integrity (API-level) | A duplicate event is processed once. An invalid signature returns 400 with no change. An amount mismatch causes no transition and increments the counter. |
| E2E-12 | Cart from a second restaurant | The "Replace cart?" dialog appears. Confirming replaces the cart; cancelling keeps it. |
| E2E-13 | Access token expires during checkout (short TTL in test config) | Silent refresh. The order is created once. |
| E2E-S1 (nightly) | E2E-01 with Stripe test mode: card `4242…`, decline `…9995`, 3-D Secure `…3155` | Same as E2E-01 and E2E-05. Webhooks arrive through the Stripe CLI. |

**MVP is accepted when:**
* E2E-01 to E2E-13 are green in CI
* E2E-S1 is green at least once against Stripe test mode
* all backend ITs and frontend tests are green with 0 skipped

---

## TASK 7: Dependency-ordered backlog

Effort is in engineer-days for one experienced engineer and **includes tests**. Ranges reflect the risk of first-time integration.

### M0: Local run and Phase 1 verification (1–2 d)

See Task 1.

| | |
|---|---|
| **Files** | `backend/api-gateway/src/main/resources/application-local.yaml`, `backend/user-service/src/main/resources/application-local.yaml`, new `*/src/test/java/.../LocalProfileConfigurationTest.java` (both services), `README.md`. Remove `backend/user-service/{mvnw,mvnw.cmd,.mvn/,.gitignore,.gitattributes}`. |
| **DoD** | Task 1 DoD |

### M1: Admin bootstrap (0.5 d) — depends on M0

| | |
|---|---|
| **Files** | `user-service/.../user/application/AdminBootstrap.java` (an `ApplicationRunner`: if `foodflow.bootstrap.admin-email` is set, grant `ADMIN` to that **already-registered** user, idempotently; logged to `SECURITY`; no password in config), `user/domain/User.java` (`grantRole`), `application.yaml` (`ADMIN_BOOTSTRAP_EMAIL:` empty default). |
| **Tests** | `AdminBootstrapTest`: unset does nothing, unknown e-mail logs a warning without failing, grants once, is idempotent. `AdminBootstrapIT`. |
| **DoD** | An admin can be created with no SQL. The role appears in the token after the next refresh. |

### M2: commerce-service skeleton and contracts (2 d) — depends on M0

This milestone is the **merge-conflict firewall**: it lands everything that parallel streams would otherwise fight over.

| | |
|---|---|
| **Files: new module** | `backend/commerce-service/pom.xml` (parent `foodflow-parent`; starter, event-contracts, webmvc, validation, security, oauth2-resource-server, data-jpa, flyway, postgresql, kafka, apicurio serde, springdoc, actuator, opentelemetry; same test dependencies as user-service). `src/main/java/com/foodflow/commerce/CommerceServiceApplication.java`. `common/config/SecurityConfiguration.java` (**all** URL rules for the MVP, written once). `common/config/OpenApiConfiguration.java`. `common/money/Money.java` (shared kernel). Packages `restaurant`, `menu`, `cart`, `order`, `payment` with `package-info.java`. `src/main/resources/application.yaml`, `application-local.yaml`, `src/test/resources/application-test.yaml`. **Empty, reserved** migration files `V1__restaurants.sql` … `V6__outbox.sql`, each owned by one stream. `src/test/.../ArchitectureTest.java` (layering, module slices free of cycles, `payment` must not depend on `order` internals). `support/AbstractIntegrationTest.java`, `support/IntegrationTestContainers.java`. |
| **Files: module ports** | Interfaces only: `menu/application/MenuCatalog` (price lookup for cart and checkout), `cart/application/CartCheckout`, `payment/application/PaymentProvider`, `payment/application/PaymentService` (`openPayment`, `requestCapture`, `requestCancel`), `order/application/OrderPaymentCallbacks` (`onAuthorized`, `onCaptureFailed`, implemented by `order`, called by `payment`). |
| **Files: modified** | `backend/pom.xml` (add `<module>commerce-service</module>`). `infra/docker/postgres/20-foodflow-init.sh` (`create_service_db commerce_svc commerce_db "$COMMERCE_DB_PASSWORD"`). `infra/docker/docker-compose.yml` (pass `COMMERCE_DB_PASSWORD`). `infra/docker/.env.example`. New `.github/workflows/commerce-service.yml` (copy of user-service.yml). Gateway: `GatewayProperties.java`, `RouteConfiguration.java`, `GatewaySecurityConfiguration.java`, `application.yaml`, `application-local.yaml`, `GatewayEdgeTest.java` (all routes from §4.5 at once). `README.md`. New `docs/adr/0011-mvp-modular-commerce-service.md`. `docs/architecture/ARCHITECTURE.md` (§3 and §12 reference ADR-0011). |
| **Endpoints** | Only `/actuator/health` |
| **Tests** | `CommerceServiceApplicationIT` (context starts, Flyway runs on an empty schema, health UP). `ArchitectureTest`. Gateway edge tests. |
| **DoD** | Service starts with `local`. CI workflow green. Gateway routes and security rules merged. Port interfaces and API contract (this document §2–§4) agreed. |

### M3: Restaurant and Menu backend (3–4 d) — depends on M2 (and M1 for admin approval testing)

| | |
|---|---|
| **Files** | `restaurant/{api/RestaurantController, api/PartnerRestaurantController, api/AdminRestaurantController, api/RestaurantDtos, application/RestaurantService, application/RestaurantQueries, domain/Restaurant, domain/RestaurantStatus, domain/RestaurantRepository, domain/RestaurantExceptions, infrastructure/persistence/JpaRestaurantRepository}`. `menu/{api/MenuController, api/PartnerMenuController, api/MenuDtos, application/MenuService, application/MenuCatalogImpl, domain/MenuCategory, domain/MenuItem, domain/MenuRepository, domain/MenuExceptions, infrastructure/persistence/*}`. `V1__restaurants.sql`, `V2__menu.sql`. |
| **Endpoints / tables / tests** | §2.2–§2.4 |
| **DoD** | `RestaurantMenuIT` green. OpenAPI regenerated to `docs/api/commerce-service.openapi.json` (copy `OpenApiDocumentationTest`). Coverage ≥ 80% on domain and application. |

### M4: Cart backend (1.5–2 d) — depends on M3 (`MenuCatalog` implementation)

| | |
|---|---|
| **Files** | `cart/{api/CartController, api/CartDtos, application/CartService, application/CartPricing, application/CartCheckoutImpl, domain/Cart, domain/CartLine, domain/CartRepository, domain/CartExceptions, infrastructure/persistence/*, application/CartHousekeepingJob}`. `V3__cart.sql`. |
| **Tests** | `CartTest`, `CartServiceTest` (mismatch → 409 and replace, unavailable, line limits, live pricing), `CartControllerTest`, `CartIT`. |
| **DoD** | Cart persists across logins. Live pricing and problem flags verified by IT. |

### M5: Checkout and Order backend (4–5 d) — depends on M4 and on the M2 `PaymentService` port (fake implementation until M6 lands)

| | |
|---|---|
| **Files** | `order/{api/OrderController, api/PartnerOrderController, api/OrderDtos, application/CheckoutService, application/OrderService, application/OrderPaymentCallbacksImpl, application/PaymentDeadlineJob, application/AcceptanceDeadlineJob, domain/Order, domain/OrderItem, domain/OrderStatus, domain/OrderStatusChange, domain/OrderRepository, domain/CheckoutIdempotency, domain/OrderExceptions, infrastructure/persistence/*}`. `V4__orders.sql`. Configuration `foodflow.orders.payment-timeout: 30m`, `acceptance-timeout: 10m`. |
| **Endpoints / tests** | §3.3–§3.5 |
| **DoD** | `CheckoutConcurrencyIT` and `OrderLifecycleIT` green with the fake provider. No provider call inside any transaction (enforced by an ArchUnit rule or code review checklist). |

### M6: Payments (4–5 d) — depends on M2 ports; integrates with M5

| | |
|---|---|
| **Files** | `payment/{api/PaymentSessionController, api/StripeWebhookController, api/FakePaymentController (@ConditionalOnProperty), api/PaymentDtos, application/PaymentServiceImpl, application/WebhookProcessor, application/PaymentOperationWorker, application/PaymentReconciliationJob, application/PaymentProperties, domain/Payment, domain/PaymentStatus, domain/PaymentOperation, domain/PaymentRepository, domain/PaymentExceptions, infrastructure/stripe/StripePaymentProvider, infrastructure/stripe/StripeConfiguration, infrastructure/fake/FakePaymentProvider, infrastructure/fake/FakePaymentGuard, infrastructure/persistence/*}`. `V5__payments.sql`. `foodflow-parent/pom.xml` (`stripe-java` version property). |
| **Endpoints / tables / tests** | §4 |
| **DoD** | `PaymentLifecycleIT` and `StripeWebhookIT` green. One manual Stripe test-mode run (once an account is available) recorded in the PR. Fake guard verified by test. |

### M7: Order events via outbox (1 d) — depends on M5 (deferrable)

| | |
|---|---|
| **Files** | `libs/foodflow-event-contracts/src/main/avro/order/OrderStatusChanged.avsc`, `Topics.java` (`ORDER_EVENTS`), `UserRegisteredSchemaTest`-style schema test, `order/infrastructure/messaging/OutboxOrderEventPublisher.java`, `V6__outbox.sql`, `foodflow.outbox.enabled: true`. |
| **Tests** | `OrderEventsOutboxIT` (transition → record on `foodflow.order.events.v1` with key `orderId`). Unit test that the event is appended inside the transition transaction. |
| **DoD** | Every order transition produces exactly one event after commit. Ordering still works with Kafka stopped (events publish when it returns). |

### M8: Customer frontend (5–6 d) — contract from M2; can mock with MSW before M3–M6 land

| | |
|---|---|
| **Files** | `frontend/apps/customer-web/src/app/routes.tsx` (**all** MVP routes with stub pages, landed first by one person), `app/AppLayout.tsx` (role nav). `features/auth/{guards.tsx (RequireRole, RoleLanding), RegisterPage.tsx (account type), api.ts}`. `shared/api/schema.d.ts` (generated) and `shared/api/types.ts`. `shared/ui/{Button,Card,Dialog,Badge,QuantityStepper,EmptyState,ErrorAlert}.tsx`. `shared/money.ts`. `features/restaurants/{api.ts,RestaurantListPage.tsx,MenuPage.tsx}`. `features/cart/{api.ts,hooks.ts,CartPage.tsx,ReplaceCartDialog.tsx}`. `features/checkout/{api.ts,CheckoutPage.tsx,AddressPicker.tsx,useIdempotencyKey.ts}`. `features/payment/{api.ts,PaymentPage.tsx,StripePayment.tsx,FakePayment.tsx}`. `features/orders/{api.ts,OrderStatusPage.tsx,OrderHistoryPage.tsx,StatusTimeline.tsx}`. `styles.css` (breakpoints). `package.json` (`@stripe/stripe-js`, `@stripe/react-stripe-js`, `openapi-typescript`, with versions verified at install under the existing pnpm release-age policy). `.env.example` (`VITE_STRIPE_PUBLISHABLE_KEY`, `VITE_PAYMENT_PROVIDER`). |
| **Tests** | Per-page Vitest and MSW tests (Task 5). `useIdempotencyKey.test.ts`. `money.test.ts`. |
| **DoD** | `pnpm verify` green. Customer journey works manually against the real backend. |

### M9: Partner and admin frontend (3–4 d) — same contract; parallel with M8 after the routes file lands

| | |
|---|---|
| **Files** | `features/partner/{api.ts, MyRestaurantsPage.tsx, RestaurantForm.tsx, RestaurantSettingsPage.tsx, MenuEditorPage.tsx, MenuItemForm.tsx, OrderQueuePage.tsx}`. `features/admin/{api.ts, RestaurantApprovalsPage.tsx}`. |
| **Tests** | Page tests: queue actions, 409 handling, polling, approval flow. |
| **DoD** | An owner can onboard, build a menu and process an order end-to-end manually. |

### M10: E2E suite and MVP release readiness (3–4 d) — depends on M1–M9

| | |
|---|---|
| **Files** | `frontend/e2e/{playwright.config.ts, fixtures/seed.ts, journey.spec.ts, pricing.spec.ts, payments.spec.ts, authorization.spec.ts, timeouts.spec.ts}`. `scripts/e2e-stack.sh` (start compose and services, wait for health). `.github/workflows/e2e.yml`. Gitleaks and dependency scanning step added to CI (minimum security gate for an MVP that handles payments). `docs/runbooks/mvp-local.md` (start-up, Stripe CLI, admin bootstrap). |
| **Tests** | Task 6 |
| **DoD** | MVP acceptance (Task 6) met. No critical or high dependency findings unresolved. README updated. |

### Effort summary

| Milestone | Effort (d) |
|---|---|
| M0 | 1–2 |
| M1 | 0.5 |
| M2 | 2 |
| M3 | 3–4 |
| M4 | 1.5–2 |
| M5 | 4–5 |
| M6 | 4–5 |
| M7 | 1 |
| M8 | 5–6 |
| M9 | 3–4 |
| M10 | 3–4 |
| **Total** | **28–35.5 engineer-days ≈ 6–7 weeks solo** |

With 3 parallel streams (Task 8) the elapsed time is about **3–4 weeks**. That is shorter than the 10–14 engineer-weeks MVP estimate in the status report, which assumed separate microservices and a distributed saga (D1, D2).

---

## TASK 8: Parallel work without conflicts or duplication

**Rule:** M0 and M2 land first, serially. M2 deliberately pre-creates every shared touch-point so that later streams only *add files inside their own package*.

| Stream | Owner scope (exclusive) | Starts after | Must not touch |
|---|---|---|---|
| **A: Catalog → Cart → Orders** (backend) | `commerce/restaurant/**`, `menu/**`, `cart/**`, `order/**`; `V1`–`V4` migrations | M2 | `payment/**`, `V5` |
| **B: Payments** (backend) | `commerce/payment/**`; `V5__payments.sql`; `stripe-java` in parent POM. Then M7 (`V6`, event contracts). | M2. Develops against the `OrderPaymentCallbacks` port with a test double, and integrates with M5 at the end. | `order/**` (talks only through the ports) |
| **C: Customer UI** | `frontend/.../features/{restaurants,cart,checkout,payment,orders}/**` | M2 plus the routes/UI-kit PR (first PR of C, about 0.5 d) | `features/partner/**`, `features/admin/**` |
| **D: Partner and admin UI** | `frontend/.../features/{partner,admin}/**` | Stream C's routes/UI-kit PR | Customer feature folders |
| **E: Platform** | M1 (user-service), M10 E2E harness and CI | M0 (M1 any time; the E2E harness skeleton can start in parallel and fill in scenarios as features land) | Service business code |

**Conflict hot-spots and how they are neutralized**

| Hot-spot | Resolution |
|---|---|
| Flyway version numbers | V1–V6 reserved and created empty in M2, one owner each. New migrations after the MVP use the next number only after rebasing. |
| `SecurityConfiguration` (commerce) and gateway routes and security | Written completely in M2. Streams do not edit them. |
| `foodflow-parent/pom.xml` | Only stream B adds a property (`stripe-java`). Other dependency changes go through one PR. |
| Cross-module calls (cart ↔ menu, order ↔ payment) | Only via the M2 port interfaces. ArchUnit fails the build on direct use of another module's `domain` or `infrastructure`. This prevents duplicated pricing logic: pricing exists **once**, in `menu.application.MenuCatalog` plus `cart.application.CartPricing`, and is reused by checkout. |
| Money arithmetic | One `common/money/Money` (M2). The frontend has one `shared/money.ts`. |
| `frontend/routes.tsx`, `AppLayout.tsx`, `shared/ui/**`, `styles.css` | Landed in stream C's first PR. Afterwards only additive changes, and UI-kit components get one owner (C). |
| `docs/api/commerce-service.openapi.json` | Generated by a test. Regenerate after rebase rather than merging the file by hand. |
| `event-contracts` | Only stream B (M7) |

**Critical path:** M0 → M2 → M3 → M4 → M5 → (M6 integration) → M10. Stream B and both UI streams finish within the critical-path window.

---

## Deferred (explicitly out of MVP)

* Live courier tracking and delivery-service
* Notification-service and e-mail (therefore also e-mail verification and password reset)
* SSE
* Partner portal as a separate app
* Courier PWA
* Geo search and PostGIS
* Opening-hours schedules
* Variants and add-ons
* Refunds UI (Stripe dashboard instead)
* Promotions, tax and tips
* Ratings
* `jti` denylist (accepted risk: tokens stay valid for up to 15 minutes)
* Kubernetes, Terraform, CD
* k6 load tests
* Grafana dashboards and alerts
* Advanced analytics

---

## FIRST implementation task (awaiting approval)

**M0, step 3: make the `local` profiles startable.** Define the referenced variables in `backend/api-gateway/src/main/resources/application-local.yaml` and `backend/user-service/src/main/resources/application-local.yaml`, and add a `LocalProfileConfigurationTest` per service that proves the placeholder guard passes with the `local` profile.

* This is the only part of M0 that is a code change.
* It can be completed and unit-tested **without Docker**.
* It removes a verified start-up defect that otherwise blocks every later milestone.
* Installing Docker (M0 step 1) is a machine prerequisite on your side and can happen in parallel.

No implementation will start until you approve.
