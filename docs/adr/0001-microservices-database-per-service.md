# ADR-0001: Microservices with database-per-service

* Status: Accepted
* Date: 2026-11-07

## Context
FoodFlow has distinct domains (identity, catalog, cart, ordering, payments, logistics, messaging)
with very different scaling, consistency and release-cadence needs. Teams must be able to ship
one domain without coordinating releases with others.

## Decision
Build nine independently deployable services: api-gateway, user, restaurant, menu, cart, order,
payment, delivery and notification.

Each service **exclusively owns its data store**:
* Its own database and credentials.
* No cross-service joins and no shared tables.

Other services get that data only through the owning service's API or its published events.

## Consequences
* Each service can scale, deploy and pick its storage technology independently.
* Cross-service consistency is eventual and needs sagas and the outbox (ADR-0004, ADR-0006).
* Reporting across domains needs event-fed read models, not direct database access.
* More operational surface (9 deployables), offset by a shared platform starter and Helm chart.
