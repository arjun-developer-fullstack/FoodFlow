package com.foodflow.events;

import org.apache.avro.util.ClassSecurityValidator;
import org.apache.avro.util.ClassSecurityValidator.ClassSecurityPredicate;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Avro 1.12+ refuses to instantiate classes outside an allow-list (deserialization hardening).
 * This adds exactly the generated FoodFlow event package to the allow-list, keeping Avro's
 * default trusted classes; nothing else is opened up.
 *
 * <p>Idempotent and thread-safe. Producers/consumers call {@link #install()} once at startup
 * (the platform starter does this automatically for the outbox).
 */
public final class AvroClassTrust {

    /** Package prefix of all generated event classes. */
    public static final String EVENTS_PACKAGE_PREFIX = "com.foodflow.events.";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private AvroClassTrust() {
    }

    public static void install() {
        if (INSTALLED.compareAndSet(false, true)) {
            ClassSecurityPredicate current = ClassSecurityValidator.getGlobal();
            ClassSecurityPredicate foodflowEvents = AvroClassTrust::isFoodflowEventClass;
            ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(current, foodflowEvents));
        }
    }

    static boolean isFoodflowEventClass(Class<?> type) {
        return type.getName().startsWith(EVENTS_PACKAGE_PREFIX);
    }
}
