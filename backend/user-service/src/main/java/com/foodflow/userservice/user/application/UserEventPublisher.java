package com.foodflow.userservice.user.application;

import com.foodflow.userservice.user.domain.User;

/**
 * Outbound port for user domain events. Implementations must take part in the caller's
 * transaction (transactional outbox), so an event exists if and only if the change committed.
 */
public interface UserEventPublisher {

    void userRegistered(User user);
}
