# ADR-0006: Orchestrated order saga; authorize-then-capture payments

* Status: Accepted
* Date: 2026-11-07

## Context
Placing an order spans Order, Payment, the restaurant's decision and Delivery. A distributed
transaction (2PC) is not an option. Customers must never be charged for orders a restaurant rejects.

## Decision
* **Order Service orchestrates** the saga with an explicit state machine (`orders.status` +
  `saga_state`), reacting to events and emitting the next one.
* Payment is **authorized** at checkout and **captured** only after the restaurant accepts.
  * A rejection or acceptance timeout (default 5 minutes) triggers a **void**.
  * A cancellation after capture triggers a **refund**.
* `POST /orders` requires an `Idempotency-Key`. The Payment provider calls also carry idempotency
  keys.
* Timeouts are driven by `saga_state.deadline_at` and a scheduled sweeper (ShedLock-style
  single-runner).

## Consequences
* The whole order lifecycle is visible and testable in one service.
* Order Service is a coordination hotspot. It holds no logic belonging to other domains; it only
  sequences steps.
* Every step and compensation must be idempotent and covered by integration tests.
