package com.foodflow.userservice.support;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.concurrent.atomic.AtomicInteger;

/** Transaction manager for unit tests with in-memory repositories; counts transactions. */
public class NoOpTransactionManager implements PlatformTransactionManager {

    private final AtomicInteger begun = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        begun.incrementAndGet();
        active.incrementAndGet();
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
        active.decrementAndGet();
    }

    @Override
    public void rollback(TransactionStatus status) {
        active.decrementAndGet();
    }

    /** True while a transaction is open (used to assert that hashing happens outside transactions). */
    public boolean inTransaction() {
        return active.get() > 0;
    }

    public int transactionsBegun() {
        return begun.get();
    }
}
