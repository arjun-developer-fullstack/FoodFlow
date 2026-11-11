package com.foodflow.userservice.user.domain;

import java.util.Locale;

/** E-mail normalization: addresses are stored and compared trimmed and lower-cased. */
public final class EmailAddress {

    private EmailAddress() {
    }

    public static String normalize(String email) {
        if (email == null) {
            throw new IllegalArgumentException("email must not be null");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
