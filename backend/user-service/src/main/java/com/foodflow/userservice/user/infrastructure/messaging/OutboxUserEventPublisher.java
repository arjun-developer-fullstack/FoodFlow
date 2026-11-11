package com.foodflow.userservice.user.infrastructure.messaging;

import com.foodflow.events.Topics;
import com.foodflow.events.common.EventMetadata;
import com.foodflow.events.user.UserRegistered;
import com.foodflow.platform.id.UuidV7;
import com.foodflow.platform.outbox.OutboxMessage;
import com.foodflow.platform.outbox.OutboxWriter;
import com.foodflow.platform.outbox.TraceparentProvider;
import com.foodflow.platform.web.CorrelationId;
import com.foodflow.userservice.user.application.UserEventPublisher;
import com.foodflow.userservice.user.domain.Role;
import com.foodflow.userservice.user.domain.User;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Publishes user events through the transactional outbox (topic {@value Topics#USER_EVENTS}). */
@Component
class OutboxUserEventPublisher implements UserEventPublisher {

    static final String AGGREGATE_TYPE = "User";
    static final String USER_REGISTERED = "UserRegistered";

    private final OutboxWriter outbox;
    private final TraceparentProvider traceparent;
    private final Clock clock;
    private final String source;

    OutboxUserEventPublisher(OutboxWriter outbox, ObjectProvider<TraceparentProvider> traceparent, Clock clock,
                             @Value("${spring.application.name}") String source) {
        this.outbox = outbox;
        this.traceparent = traceparent.getIfAvailable(() -> TraceparentProvider.NONE);
        this.clock = clock;
        this.source = source;
    }

    @Override
    public void userRegistered(User user) {
        UUID eventId = UuidV7.generate(clock);
        Instant now = Instant.now(clock);
        UserRegistered event = UserRegistered.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                        .setEventId(eventId.toString())
                        .setEventType(USER_REGISTERED)
                        .setSource(source)
                        .setOccurredAt(now)
                        .setCorrelationId(CorrelationId.current())
                        .setTraceparent(traceparent.currentTraceparent())
                        .build())
                .setUserId(user.getId().toString())
                .setEmail(user.getEmail())
                .setFullName(user.getFullName())
                .setRoles(user.getRoles().stream().map(Role::name).sorted().toList())
                .setRegisteredAt(user.getCreatedAt())
                .build();
        outbox.append(new OutboxMessage(eventId, AGGREGATE_TYPE, user.getId().toString(), USER_REGISTERED,
                Topics.USER_EVENTS, event));
    }
}
