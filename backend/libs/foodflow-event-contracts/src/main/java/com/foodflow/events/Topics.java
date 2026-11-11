package com.foodflow.events;

/**
 * Kafka topic names. Naming convention: {@code foodflow.<domain>.events.v<major>}.
 * The record key is always the aggregate id, which guarantees ordering per aggregate.
 */
public final class Topics {

    public static final String USER_EVENTS = "foodflow.user.events.v1";

    private Topics() {
    }
}
