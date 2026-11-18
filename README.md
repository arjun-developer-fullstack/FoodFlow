# FoodFlow

Enterprise food ordering and delivery platform: Java 21 / Spring Boot 4.1 microservices,
Kafka event-driven communication, React + TypeScript frontends, designed for deployment to AWS EKS.

> **Status:** Milestone M0. Implemented so far: API gateway, user-service (registration, login,
> JWT issuing, refresh-token rotation, logout, profiles and addresses), shared platform libraries
> and the customer web app's auth flow. The remaining services and the cloud deployment are planned.

* Architecture: [`docs/architecture/ARCHITECTURE.md`](docs/architecture/ARCHITECTURE.md)
* Decisions: [`docs/adr/`](docs/adr)
* API contracts: [`docs/api/`](docs/api) (OpenAPI 3.1, generated from the code by tests)

## Repository layout

```
.github/workflows/   CI: reusable Java pipeline + one path-filtered workflow per deployable
backend/
  foodflow-parent/   Shared versions & build conventions (inherited by every module)
  libs/
    foodflow-event-contracts/   Avro event schemas + generated classes
    foodflow-platform-core/     Config guard, JWT validation rules, correlation id (servlet + reactive)
    foodflow-platform-starter/  Servlet auto-config: problem details, security, transactional outbox
  api-gateway/       Spring Cloud Gateway: routing, edge JWT, rate limiting, CORS    :8080
  user-service/      Identity, authentication (JWT issuer), profiles, addresses     :8081
  ...                restaurant :8082, menu :8083, cart :8084, order :8085,
                     payment :8086, delivery :8087, notification :8088 (later phases)
frontend/            pnpm workspace
  apps/customer-web/ React 19 + TypeScript customer app (Vite dev server :5173)
infra/
  docker/            Local infrastructure (docker compose)
  k8s/ terraform/    Cloud deployment (planned, Phase 7; not yet in the repository)
docs/                Architecture, ADRs, OpenAPI contracts (AsyncAPI planned)
```

## Prerequisites

* JDK 21
* Docker Desktop (or another Docker engine). Needed for the local infrastructure and for the
  Testcontainers integration tests (`*IT`). Without Docker those tests are reported as
  **skipped**; they always run in CI.
* Node.js 22+ with corepack (`corepack enable` once; pnpm is pinned in `frontend/package.json`)

## Local development

```bash
# 1. Local secrets (git-ignored); replace every "change-me" value
cp infra/docker/.env.example infra/docker/.env

# 2. Start infrastructure (Postgres+PostGIS, MongoDB, Redis, Kafka, Schema Registry, Grafana LGTM)
cd infra/docker && docker compose up -d && cd ../..

# 3. Build & test all backend modules
cd backend
./mvnw verify                                    # Windows: mvnw.cmd verify

# 4. Run the services with the "local" profile (reads infra/docker/.env, uses an ephemeral JWT key)
./mvnw -pl user-service spring-boot:run -Dspring-boot.run.profiles=local
./mvnw -pl api-gateway  spring-boot:run -Dspring-boot.run.profiles=local

# 5. Run the customer web app (proxies /api to the gateway on :8080)
cd ../frontend
pnpm install                                     # without `corepack enable`: corepack pnpm install
pnpm dev                                         # http://localhost:5173
pnpm verify                                      # lint, typecheck, tests and production build
```

| Tool | URL |
|---|---|
| Customer web | http://localhost:5173 |
| API gateway | http://localhost:8080 |
| User service Swagger UI (direct, not via gateway) | http://localhost:8081/swagger-ui.html |
| Grafana (metrics, logs, traces) | http://localhost:3000 |
| Kafka UI | http://localhost:8190 |
| Schema Registry UI | http://localhost:8181 |

### JWT signing keys outside local development

The local profile generates a throw-away signing key. Every other environment must mount a key:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out private.pem   # keep secret
openssl pkey -in private.pem -pubout -out public.pem                             # for rotation
```

Set `JWT_PRIVATE_KEY_LOCATION=file:/path/private.pem`. To rotate, deploy the new private key and
list the old public key in `JWT_PREVIOUS_PUBLIC_KEY_LOCATIONS` until old tokens have expired.

## Conventions

* Unit tests: `*Test.java` (surefire). Integration tests: `*IT.java` (failsafe, Testcontainers).
* All versions live in `backend/foodflow-parent/pom.xml`; modules do not declare versions.
* No secrets in the repository: configuration references environment variables, and startup fails
  if one is undefined.
* One database and one set of credentials per service. Never read another service's data.
* Cross-service state changes go through Kafka via the transactional outbox.
* Errors are RFC 9457 problem details with a stable `code` and a `correlationId`.

## License

[MIT](LICENSE). The Maven Wrapper scripts (`backend/mvnw`, `backend/mvnw.cmd`) are Apache Maven
files and remain under the Apache License 2.0, as stated in their headers.
