# FoodFlow — M0 Closure Plan

| | |
|---|---|
| **Date** | 2026-11-10 (plan), executed 2026-11-11 to 2026-11-12 |
| **Status** | Executed. All steps below were carried out; see §3 for the outcome and §9 for the checklist. |
| **Scope** | Close milestone M0 (local environment): Docker prerequisites, the first execution of all integration tests, local start-up of the stack, and manual auth acceptance tests. |
| **Code state at planning time** | Backend: 165 unit tests pass, 0 fail. Frontend: 20/20 pass. 17 ITs were skipped (no Docker yet). |
| **Ground rules** | No software installed without approval, no production configuration changed, no data or volumes deleted, nothing committed or pushed without approval. |

---

## 1. Local prerequisites

| Requirement | Why |
|---|---|
| A Docker engine with Compose v2 (Docker Desktop, or Docker Engine in WSL2 / Linux) | Local infrastructure (`infra/docker`) and the Testcontainers integration tests |
| On Windows: WSL2 and hardware virtualization (including nested virtualization when the machine is itself a VM) | Required by every Linux container runtime on Windows |
| JDK 21 | Backend build and tests (Maven runs through `backend/mvnw`) |
| Node.js 22+ with corepack | Frontend; pnpm is pinned in `frontend/package.json` |
| About 4–6 GB of free RAM | The compose stack while running |
| Free local ports | 3000, 4317, 4318, 5173, 5432, 6379, 8080, 8081, 8180, 8181, 8190, 9092, 27017 |
| Network access to Docker Hub, Maven Central and the npm registry | First pull of images and dependencies |

## 2. Container runtime options

| Aspect | A: Docker Desktop | B: Docker Engine inside WSL2 |
|---|---|---|
| Installation | Installer; needs local administrator rights | `wsl --install -d Ubuntu-24.04`, then Docker from `download.docker.com` inside the distro |
| Licence | Check Docker's subscription terms for your situation | Docker Engine / Moby is Apache-2.0 |
| Testcontainers from the Windows JVM | Works out of the box (named pipe) | Run the ITs **inside WSL** (needs JDK 21 there); exposing the daemon over TCP is not recommended |
| Services and frontend on Windows reaching the containers | Works out of the box | WSL2 forwards `localhost` ports by default; otherwise set `networkingMode=mirrored` in `%UserProfile%\.wslconfig` |
| Builds | Normal | Builds on `/mnt/c` are slower; never run a Windows build and a WSL build at the same time (they share `target/`) |

Both options run the compose stack unchanged. With option B, the init scripts are bind-mounted from the Windows filesystem and appear executable, so the Postgres entrypoint runs them as intended.

## 3. Outcome

* **Runtime:** option A (Docker Desktop with the WSL2 backend).
* **Integration tests:** all ran for the first time and pass (17 at the time, 19 after the acceptance-test fixes), 0 skipped.
* **Stack:** S-1 to S-6 started and verified; acceptance tests AT-01 to AT-16 and AT-B1 to AT-B8 pass.
* **Findings fixed along the way:** Kafka log directory (F3), localhost-only binding of the local profiles, refresh-cookie handling inside the reuse grace window, `Retry-After` on 423 and 429, and the session-restore notice during an outage. See ADR-0007 for the access-token revocation strategy.

---

## 4. Compose, environment variables and init scripts (final review)

### 4.1 Verified correct

| Item | Result |
|---|---|
| Required variables | Compose requires 11 variables with `:?` guards: `POSTGRES_ADMIN_USER/PASSWORD`, `MONGO_ROOT_USER/PASSWORD`, `USER_`, `RESTAURANT_`, `ORDER_`, `PAYMENT_`, `DELIVERY_`, `MENU_`, `NOTIFICATION_DB_PASSWORD`. **All 11 are in `.env.example`.** Startup fails fast if one is missing. |
| Cross-checks with the services | `user_svc`/`user_db` (init script) = user-service local profile. Topic `foodflow.user.events.v1` (topic script) = `Topics.USER_EVENTS`. Apicurio `localhost:8180/apis/registry/v3` = local profile. Redis without a password = gateway local profile. JWT issuer `http://localhost:8080` is identical in both services. |
| Images | All 9 tags exist on Docker Hub with amd64 builds. This includes the Testcontainers images `postgres:17-alpine`, `apache/kafka:4.3.2`, `apicurio/apicurio-registry:3.3.4` and `redis:8.6-alpine`. |
| Line endings | `create-topics.sh`, `20-foodflow-init.sh`, `init-users.js`, `.env.example` and `mvnw` are LF (0 CR bytes). `docker-compose.yml` has CRLF, which Compose accepts; Git normalizes it on the first commit (`.gitattributes: * text=auto eol=lf`). |
| Postgres init | One role and database per service, `REVOKE CONNECT … FROM PUBLIC`, PostGIS only where needed, `ON_ERROR_STOP=1`, passwords passed as psql variables (not interpolated into SQL text). |
| Mongo init | Per-service users with `readWrite` on their own database only. Fails if a password variable is missing. |
| Kafka | Single-node KRaft. Valid 22-character `CLUSTER_ID`. Auto-create **disabled**. `kafka-init` waits for `service_healthy` and creates 7 topics idempotently. |
| Exposure | Every host port bound to `127.0.0.1` |

### 4.2 Findings: none block M0; all are to be checked at first start

| # | Finding | Risk | Action |
|---|---|---|---|
| F1 | Init scripts run **only on an empty data volume** (Postgres `docker-entrypoint-initdb.d`, Mongo). | If the first start fails part-way, a later start silently skips initialization. | Check the init logs at the first start (S-2). Recovery needs a volume reset, which deletes local data. **That will only be done with your explicit approval** (none exists today). |
| F2 | `20-foodflow-init.sh` relies on being **executable**. Without the execute bit, the Postgres entrypoint *sources* it, and `set -euo pipefail` then leaks into the entrypoint shell. | Not a risk from `/mnt/c` or Windows mounts (files appear executable). **A risk after the first commit** on Linux or CI checkouts, because Git on Windows records mode 644. | At the first commit: `git update-index --chmod=+x infra/docker/postgres/20-foodflow-init.sh infra/docker/kafka/create-topics.sh` (included in §9). |
| F3 | The Kafka volume is mounted at `/var/lib/kafka/data`, but `KAFKA_LOG_DIRS` is not set. The broker probably writes elsewhere, so the volume is unused. | Topics and offsets may not survive container re-creation. `kafka-init` recreates the topics, so this is harmless for M0. | Verify in S-2: `docker compose exec kafka ls -A /var/lib/kafka/data` (empty means confirmed). Fix later (local config only) with your approval. |
| F4 | Apicurio uses **in-memory storage** locally. | Schemas vanish on restart; the local profile auto-registers them again. | None (intended for local) |
| F5 | The user-service local profile imports the **whole** `.env`, including the Postgres and Mongo admin passwords, into its Spring environment. | Local only. `/actuator/env` is not exposed. | Accept for M0; note for hardening |
| F6 | `.env` must stay LF. Editors such as Notepad may write CRLF. | A trailing `\r` could make Compose and Spring disagree on a password. | Verify with `tr -cd '\r' < infra/docker/.env \| wc -c` → `0` (S-1) |
| F7 | `local` profiles enable OTLP export to `localhost:4318`. | If the `observability` container is not running, the services log "Failed to export spans". Harmless. | Keep the `observability` service up |

---

## 5. Refreshing the stale Maven local repository

### 5.1 Current state (verified)

* `~/.m2/repository/com/foodflow/` (116 MB) contains artifacts from **2026-11-07**: `foodflow-parent`, `foodflow-event-contracts`, `foodflow-platform-core`, `foodflow-platform-starter`, `user-service`. There is no `api-gateway`.
* The installed **`foodflow-platform-core` jar does not contain the M0 guard fix** (no `BeanFactoryPostProcessor` in `UnresolvedPlaceholderGuard.class`).
* **Impact:** `./mvnw -pl user-service spring-boot:run` (README step 4) resolves these stale libraries, so it would run old code. In-reactor builds (`./mvnw verify` from `backend/`) are not affected.

### 5.2 Is a refresh safe? **Yes.**

| Question | Answer |
|---|---|
| What does `install` write? | Only `~/.m2/repository/com/foodflow/**`. Our own 5 SNAPSHOT artifacts are overwritten and `api-gateway` is added (about +70 MB). **No third-party artifact is modified.** |
| Network? | None with `-o` (offline). All plugins are cached, including `maven-install-plugin` 3.1.3/3.1.4, the Maven 3.10.0 wrapper distribution, surefire, failsafe and jar. There is no `settings.xml`, so no mirror or credentials are involved. |
| Which wrapper? | **`backend/mvnw` only.** `backend/user-service/mvnw` is the Spring Initializr leftover. It uses the same Maven 3.10.0, but run from that directory it would build user-service alone against the stale libraries. |
| Reversible? | Yes. Back up first (116 MB), restore by copying back. |

**Procedure (Windows, Git Bash, from the repo root):**

```bash
cp -a ~/.m2/repository/com/foodflow ~/.m2/foodflow-backup-20261110          # backup (no deletion)
cd backend
./mvnw -B -o clean install                    # full build + unit tests; ITs skip without Docker on Windows
unzip -p ~/.m2/repository/com/foodflow/foodflow-platform-core/0.1.0-SNAPSHOT/foodflow-platform-core-0.1.0-SNAPSHOT.jar \
  com/foodflow/platform/config/UnresolvedPlaceholderGuard.class | grep -c BeanFactoryPostProcessor   # expect ≥ 1
ls ~/.m2/repository/com/foodflow/                                            # api-gateway now present
```

* Rollback, if ever needed: `cp -a ~/.m2/foodflow-backup-20261110/. ~/.m2/repository/com/foodflow/`.
* **With option B**, the ITs run inside WSL, which has its own empty `~/.m2`. There, run `./mvnw -B install -DskipTests` once **online** (no `-o`).

---

## 6. Sequential verification of the 17 integration tests

The ITs start their **own** containers through Testcontainers. They do **not** use the compose stack, so run them first, as an isolated check. They have **never run before**, so failures here are expected discoveries, not regressions. **Each failure gets a root-cause report before any fix is made.**

### 6.1 Environment

`$REPO` in this section and in §7 is the root of your clone, e.g. `REPO=$(git rev-parse --show-toplevel)`.
In WSL the same clone is reached through `/mnt/<drive>/…`.

| | Option A: Windows + Docker Desktop | Option B: inside WSL Ubuntu |
|---|---|---|
| Shell | Git Bash, `cd $REPO/backend` | `wsl -d Ubuntu-24.04`, then `cd $REPO/backend` |
| Maven flags | `-o` (offline; cache complete) | No `-o` on the first run |
| Pre-check | `docker info` | `docker info` and `java -version` (21) |

`$MVN` below means `./mvnw -B` (option B: `./mvnw -B` without `-o`; option A: `./mvnw -B -o`). Every command runs **only the ITs of one class**: `-Dtest=none` skips unit tests and `-Dit.test` selects the IT class.

```bash
IT() { $MVN -pl "$1" verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test="$2"; }
```

Prerequisite: §5 install has been done in the same environment, so `-pl <service>` resolves fresh libraries.

| Step | Command | Tests | Proves | Infra pulled | Typical first-run problems |
|---|---|---|---|---|---|
| T-1 | `IT user-service UserServiceApplicationIT` | 4: `contextLoadsAndMigrationsAreApplied`, `healthProbesArePublic`, `jwksPublishesExactlyOnePublicSigningKey`, `onlyHealthAndInfoActuatorEndpointsAreExposed` | Full context, **Flyway V1 on real PostgreSQL**, `ddl-auto: validate` mappings, actuator exposure | postgres, kafka, apicurio, ryuk | "Could not find a valid Docker environment" (daemon not reachable). Apicurio slow on first pull (2-minute wait limit). |
| T-2 | `IT user-service AuthFlowIT` | 5: register/sign-in/refresh/sign-out, refresh-reuse family revocation, lockout persisted, duplicate e-mail case-insensitive, forged tokens rejected | End-to-end auth against a real DB | (cached) | SQL or locking dialect issues (`FOR UPDATE`, family lock) never exercised before |
| T-3 | `IT user-service AddressAndAdminIT` | 3: address default invariant, cross-user isolation, admin suspension revokes sessions | Partial unique index `ux_addresses_one_default`, admin rules | (cached) | Constraint ordering on default switch |
| T-4 | `IT user-service AuthConcurrencyIT` | 3: parallel wrong passwords all counted, parallel registrations create one account, parallel refreshes rotate once | Row and family locks under contention | (cached) | Deadlocks or timeouts. Re-run once to rule out flakiness before diagnosing. |
| T-5 | `IT user-service UserRegisteredOutboxIT` | 1: registration publishes `UserRegistered` to Kafka | Outbox relay, Avro serde, Apicurio registration, topic key | (cached) | Registry URL/serde config, topic auto-create (the test broker allows it) |
| T-6 | `IT api-gateway RateLimitIT` | 1: sign-in limited per client IP, excess gets 429 | Redis token bucket, Lua script | redis | Redis container `@ServiceConnection(name="redis")` |
| **Total** | | **17** (16 + 1) | | | |

### 6.2 After each step: evidence to record

```bash
# replace <module>/<Class> accordingly
grep -o 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' \
  <module>/target/failsafe-reports/TEST-*<Class>.xml          # expect skipped="0" failures="0" errors="0"
cat <module>/target/failsafe-reports/*<Class>.txt               # summary
docker ps --filter label=org.testcontainers=true                # should be empty afterwards (Ryuk cleanup)
```

### 6.3 Final gate: identical to CI

```bash
$MVN verify                                                    # all modules, unit tests + all ITs
grep -l 'skipped="[1-9]' */target/failsafe-reports/TEST-*.xml && echo "FAIL: ITs skipped" || echo "OK: no skipped ITs"
grep -ho 'tests="[0-9]*"' */target/failsafe-reports/TEST-*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {print s " ITs (expect 17)"}'
grep -ho 'failures="[0-9]*" *' */target/failsafe-reports/TEST-*.xml | sort | uniq -c   # expect only failures="0"
```

(`bc` and `jq` are not part of a default Git Bash for Windows install, so the commands use `awk`, `grep` and `sed` only.)

**Pass criteria:**
* 165 unit tests and **17/17 ITs pass, 0 skipped**
* `docs/api/user-service.openapi.json` unchanged (`sha256sum` before and after)
* No leftover Testcontainers containers

---

## 7. Complete start-up procedure (backend, gateway, frontend)

Run order: infrastructure, then user-service, then gateway, then frontend. Each step has a health check that must pass before continuing. With option B, run the S-steps marked 🐧 inside WSL and the others on Windows.

### S-1: Local secrets (one time)

```bash
cd $REPO/infra/docker
[ -e .env ] && echo ".env already exists: keep it" || {
  cp .env.example .env
  while grep -q 'change-me' .env; do sed -i "0,/change-me/s//$(openssl rand -hex 24)/" .env; done
}
grep -c 'change-me' .env               # expect 0
tr -cd '\r' < .env | wc -c             # expect 0 (LF only)
```

`.env` is git-ignored. The guard skips creation if the file already exists, so an existing `.env` is never overwritten.

### S-2: Infrastructure 🐧

> **Git Bash on Windows:** MSYS converts arguments that look like absolute paths, so
> `docker compose exec kafka /opt/kafka/bin/…` becomes `C:/Program Files/Git/opt/kafka/bin/…` and fails with
> `OCI runtime exec failed … no such file or directory`. Run `export MSYS_NO_PATHCONV=1` first (or prefix the
> command). WSL shells and CI are not affected. Commands without an absolute container path (such as the `psql` checks) work either way.

```bash
cd $REPO/infra/docker
export MSYS_NO_PATHCONV=1                           # Git Bash only (see note above); harmless elsewhere
docker compose config --quiet && echo "compose file + .env valid"
docker compose up -d
docker compose ps                                     # postgres, mongodb, redis, kafka: healthy; others: running; kafka-init: exited (0)
docker compose logs kafka-init | grep -c '^ok:'       # expect 7
docker compose logs postgres | grep -iE 'error|fatal' # expect nothing from 20-foodflow-init.sh
docker compose exec postgres psql -U foodflow_admin -d postgres -tAc \
  "select datname from pg_database where datname like '%\_db' order by 1"   # delivery_db order_db payment_db restaurant_db user_db
docker compose exec kafka ls -A /var/lib/kafka/data   # finding F3 (fixed 2026-11-11 via KAFKA_LOG_DIRS): expect meta.properties + topic partition dirs
```

### S-3: Reachability from Windows (option B only; Git Bash on Windows)

```bash
for p in 5432 6379 9092 8180 27017 4318; do (echo > /dev/tcp/127.0.0.1/$p) 2>/dev/null && echo "$p ok" || echo "$p NOT reachable"; done
```

If any port is not reachable, set `networkingMode=mirrored` under `[wsl2]` in `%UserProfile%\.wslconfig`, run `wsl --shutdown`, and repeat S-2 and S-3. This is a user-level setting; approval is still requested before changing it.

### S-4: user-service (terminal 1, Windows)

```bash
cd $REPO/backend
./mvnw -o -pl user-service spring-boot:run -Dspring-boot.run.profiles=local      # needs §5 install first
#   alternative without ~/.m2:  cd user-service && java -jar target/user-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

The working directory must be `backend/user-service` (the default for `spring-boot:run`), because the local profile imports `../../infra/docker/.env`.

Checks (another terminal):

```bash
curl -s localhost:8081/actuator/health/readiness                         # {"status":"UP"}
curl -s localhost:8081/.well-known/jwks.json | grep -o '"kid"' | wc -l   # 1
docker compose -f $REPO/infra/docker/docker-compose.yml exec postgres \
  psql -U user_svc -d user_db -tAc "select version, success from flyway_schema_history"   # 1|t
```

Swagger UI (local only): <http://localhost:8081/swagger-ui.html>

### S-5: api-gateway (terminal 2, Windows)

```bash
cd $REPO/backend
./mvnw -o -pl api-gateway spring-boot:run -Dspring-boot.run.profiles=local
```

Checks:

```bash
curl -s localhost:8080/actuator/health/readiness                                   # {"status":"UP"}
curl -s localhost:8080/.well-known/jwks.json | grep -o '"kid"' | wc -l             # 1 (proxied)
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/users/me            # 401
```

### S-6: Frontend (terminal 3, Windows)

```bash
cd $REPO/frontend
export COREPACK_ENABLE_DOWNLOAD_PROMPT=0          # pnpm 12.18.0 is already in the corepack cache
corepack pnpm install --frozen-lockfile           # verifies node_modules against the lockfile
corepack pnpm dev                                 # http://localhost:5173 (proxies /api to :8080)
```

`corepack pnpm` is used instead of `corepack enable`, which would need admin rights to write to `C:\Program Files\nodejs`.

### S-7: Observability and UIs (optional)

* Grafana: <http://localhost:3000>
* Kafka UI: <http://localhost:8190>
* Apicurio UI: <http://localhost:8181>

### Shutdown (data preserving)

* Ctrl+C in terminals 1–3.
* `docker compose stop` stops the containers and keeps the volumes. `docker compose down` (**without `-v`**) also keeps the volumes.
* **Never use `down -v` or `docker volume rm` without explicit approval.**

---

## 8. Manual acceptance tests: registration, login, refresh token, logout

* **Environment:** S-1 to S-6 running.
* **API tests** use Git Bash against the **gateway** (`:8080`), the same path the browser uses through the Vite proxy.
* **Expected values** are derived from the code: access token 15 min, refresh token 14 d, reuse grace 10 s, lockout after 5 failures for 15 min, password 12–128 characters, cookie `ff_refresh; Path=/api/v1/auth; HttpOnly; SameSite=Strict`. `Secure` is off only in the local profile.

### 8.1 Setup

```bash
GW=http://localhost:8080; PW='correct horse battery staple'; EMAIL="at-$(date +%s)@example.com"
J=$(mktemp -d); tok() { sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' "$1"; }
reg() { curl -s -D "$J/h" -o "$J/b" -c "$J/jar" -H 'Content-Type: application/json' -d "$1" "$GW/api/v1/auth/register"; }
```

### 8.2 API acceptance tests

| ID | Steps | Expected result |
|---|---|---|
| **AT-01** Register | `reg "{\"email\":\"$EMAIL\",\"password\":\"$PW\",\"fullName\":\"Acceptance Tester\"}"; cat $J/h $J/b` | `201`. `Location: /api/v1/users/me`. `Cache-Control: no-store`. `X-Correlation-Id` present. Body: `tokenType=Bearer`, `expiresIn≈900`. `Set-Cookie: ff_refresh=…; Path=/api/v1/auth; Max-Age≈1209600` (14 d, ±1 s) `; HttpOnly; SameSite=Strict`. The refresh token is **not** in the body. |
| **AT-02** Use the access token | `T=$(tok $J/b); curl -s -H "Authorization: Bearer $T" $GW/api/v1/users/me` | `200`, the e-mail lower-cased, `roles=["CUSTOMER"]`, `emailVerified=false` |
| **AT-03** Validation and duplicates | `reg '{"email":"x@example.com","password":"short","fullName":"X"}'` then repeat AT-01 with `EMAIL` in upper case | First: `400` problem with a field error. Second: `409` `code=EMAIL_ALREADY_REGISTERED`. |
| **AT-04** Login | `curl -s -D $J/h -o $J/b -c $J/jar -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" $GW/api/v1/auth/login` | `200`, new access token, new `ff_refresh` cookie |
| **AT-05** Bad credentials | Same as AT-04 with a wrong password, then with an unknown e-mail | Both return `401` `code=INVALID_CREDENTIALS` with the **same** body shape (no account enumeration) |
| **AT-06** Lockout | Register a second account. Send 5 wrong passwords, then the correct one. | Attempts 1–4: `401`. Attempt 5: `423 ACCOUNT_LOCKED`. Correct password: still `423` (for 15 min). |
| **AT-07** Refresh rotation | `cp $J/jar $J/jar-old; curl -s -D $J/h -o $J/b -b $J/jar -c $J/jar -X POST $GW/api/v1/auth/refresh` | `200`, new access token. The `ff_refresh` value in `jar` differs from `jar-old`. |
| **AT-08** Reuse detection | `sleep 11`, replay `jar-old` (`-b $J/jar-old`), then refresh with the current `jar` | Replay: `401 INVALID_REFRESH_TOKEN` and the cookie is cleared (`Max-Age=0`). Then the **current** token is also `401`: the whole session family is revoked. |
| **AT-09** Grace window (benign race) | Log in again. `cp jar jar-old`. Refresh with `jar`, then within 10 s refresh with `jar-old`, then refresh with the current `jar`. | Replay within grace: `401` without family revocation. Current token: `200`, the session survives. |
| **AT-10** Logout | `curl -s -D $J/h -b $J/jar -c $J/jar -X POST $GW/api/v1/auth/logout`, then refresh, then logout again | `204` with `ff_refresh=; Max-Age=0`. Refresh afterwards: `401`. Second logout: `204` (idempotent). |
| **AT-11** Access token after logout | `curl -H "Authorization: Bearer $T" $GW/api/v1/users/me` straight after AT-10 | `200` until the token expires (at most 15 min). **This is the documented accepted risk (no `jti` denylist yet), not a defect.** |
| **AT-12** Missing cookie | `curl -s -X POST $GW/api/v1/auth/refresh` | `401 INVALID_REFRESH_TOKEN` |
| **AT-13** Rate limiting | `for i in $(seq 15); do curl -s -o /dev/null -w '%{http_code} ' -H 'Content-Type: application/json' -d '{"email":"rl@example.com","password":"x"}' $GW/api/v1/auth/login; done` | The first ~10 return `401`, then `429` (auth bucket: burst 10, 1/s) |
| **AT-14** Downstream unavailable | Stop user-service (Ctrl+C, terminal 1), then run AT-04 | `503` problem, `code=SERVICE_UNAVAILABLE`, `Retry-After: 5`, no internal host or port in the body. Restart user-service afterwards. |
| **AT-15** Data at rest | `docker compose exec postgres psql -U user_svc -d user_db -tAc "select left(password_hash,9), length(token_hash) from users u join refresh_tokens r on r.user_id=u.id limit 3"` and grep the DB for a cookie value from `jar` | `{argon2}` prefix. `token_hash` length 64 (SHA-256 hex). **The raw cookie value is not found** in the database. |
| **AT-16** Event published | `…psql… "select event_type, published_at is not null from outbox_events order by seq desc limit 3"`; Kafka UI topic `foodflow.user.events.v1`; Apicurio UI | `UserRegistered | t`. Message key = user id. Artifact `com.foodflow.events.user.UserRegistered` is registered. |

### 8.3 Browser acceptance tests (<http://localhost:5173>, a private window, devtools open)

| ID | Steps | Expected result |
|---|---|---|
| **AT-B1** Register | Register a new account through the UI | Lands on the home page: "Hi, *name*!" and "Signed in as *e-mail*" |
| **AT-B2** Token storage | Devtools → Application | `ff_refresh` cookie is HttpOnly and SameSite=Strict. **No token** in localStorage or sessionStorage. |
| **AT-B3** Silent refresh | Reload the page | Still signed in. One `POST /api/v1/auth/refresh` returns `200`. |
| **AT-B4** Multi-tab | Open a second tab and reload both tabs quickly | Both stay signed in (refreshes serialized across tabs; no reuse revocation) |
| **AT-B5** Sign out | Click "Sign out", then reload, then press Back | Sign-in page. Stays signed out. No profile data shown. |
| **AT-B6** Error messages | Wrong password; then lock an account (AT-06) | Readable error messages; the locked state is explained |
| **AT-B7** Outage | Stop user-service, reload the page | A temporary-error message, **not** a forced sign-out. After restart, a reload signs in again. |
| **AT-B8** Key rotation on restart (observation) | Restart user-service (local profile generates a new ephemeral key), then reload | Silent refresh works, and the gateway accepts the token signed with the new key. **If the gateway rejects it (stale JWKS cache), record it as a finding.** |

**Evidence to keep:** the console output of 8.2, devtools screenshots for AT-B2/B3/B5, and any failures with their `correlationId`.

---

## 9. Final M0 checklist

| # | Task | Status | Depends on |
|---|---|---|---|
| 1 | `local` profiles fixed and tested | ✅ Done | — |
| 2 | Configuration guard runs before any bean | ✅ Done | — |
| 3 | Gateway 503/504 RFC 9457 responses | ✅ Done | — |
| 4 | 165 unit tests and 20 frontend tests green | ✅ Done | — |
| 5 | Compose, env and init review | ✅ Done (F1–F7, none blocking) | — |
| 6 | Container runtime prerequisites assessed (§1, §2) | ✅ Done | — |
| 7 | Container runtime chosen | ✅ Done (option A: Docker Desktop) | 6 |
| 8 | Container runtime available | ✅ Done | 7 |
| 9 | Refresh `~/.m2` (§5) | ✅ Done | — |
| 10 | All ITs, sequential (§6.1), then full gate (§6.3) | ✅ Done (19/19, 0 skipped) | 8, 9 |
| 11 | Stack start-up S-1…S-6 (§7) | ✅ Done | 8, 9 |
| 12 | Acceptance tests AT-01…AT-16 and AT-B1…AT-B8 (§8) | ✅ Done | 11 |
| 13 | First commit: Initializr duplicates in `backend/user-service` (`mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `.gitattributes`) left out; line endings normalized; infra scripts and `mvnw` executable (F2) | ✅ Done | 10 |
| 14 | Create the GitHub remote and push; 3 workflows green, including "no skipped ITs" | ⏳ Approval | 13 |

**M0 is closed when** rows 10, 12 and 14 are green. M1 starts only after approval.

### Next action

Publish the repository (row 14) and confirm the CI workflows are green. Container-image publishing stays disabled until it is explicitly enabled.
