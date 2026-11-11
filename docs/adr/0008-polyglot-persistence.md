# ADR-0008: Polyglot persistence per service

* Status: Accepted
* Date: 2026-11-07

## Decision
| Service | Store | Rationale |
|---|---|---|
| User, Order, Payment | PostgreSQL 17 | ACID, relational integrity, auditability |
| Restaurant, Delivery | PostgreSQL 17 + PostGIS | Geospatial search (nearby restaurants, pickup/drop-off) |
| Menu, Notification | MongoDB 8.0 | Nested, variable-shape documents; write-heavy logs |
| Cart | Redis | Ephemeral, high-frequency, TTL-based |
| Delivery (live), Gateway | Redis | GEO index for couriers; rate-limit buckets |

* Schemas are managed with Flyway (Postgres) or with versioned migration code / index
  initialisers (Mongo). `ddl-auto=validate`.
* IDs are UUIDv7. Money is stored as `BIGINT` minor units plus an ISO-4217 currency.
* Locally, one container per technology hosts separate databases and users. In the cloud there
  are separate logical databases, and Payment gets its own instance.

## Consequences
* Each domain uses the store that fits its access pattern.
* Three data technologies to operate, mitigated by managed services (RDS, Atlas, ElastiCache).
