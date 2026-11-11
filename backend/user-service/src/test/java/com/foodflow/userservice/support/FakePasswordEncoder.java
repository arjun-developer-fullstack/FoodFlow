package com.foodflow.userservice.support;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.function.BooleanSupplier;

/**
 * Fast, deterministic encoder for unit tests (real Argon2 is covered by its own test).
 * Hashes prefixed with {@code legacy:} report that they need upgrading. Optionally fails the test
 * if hashing happens while a guard condition holds (e.g. "a transaction is open").
 */
public class FakePasswordEncoder implements PasswordEncoder {

    private int matchCalls;
    private BooleanSupplier forbiddenWhen = () -> false;

    public void forbidWhen(BooleanSupplier condition) {
        this.forbiddenWhen = condition;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        guard();
        return "hashed:" + rawPassword;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        guard();
        matchCalls++;
        return encodedPassword.equals("hashed:" + rawPassword) || encodedPassword.equals("legacy:" + rawPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return encodedPassword.startsWith("legacy:");
    }

    public int matchCalls() {
        return matchCalls;
    }

    private void guard() {
        if (forbiddenWhen.getAsBoolean()) {
            throw new AssertionError("Password hashing must not run inside a database transaction");
        }
    }
}
