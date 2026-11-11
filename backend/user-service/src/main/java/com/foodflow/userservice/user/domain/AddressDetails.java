package com.foodflow.userservice.user.domain;

import com.foodflow.userservice.user.domain.UserExceptions.InvalidAddressException;

import java.util.Locale;

/**
 * Value object with the editable fields of an {@link Address}. Normalizes text and enforces
 * cross-field rules; single-field formats are validated earlier at the API boundary.
 */
public record AddressDetails(String label, String line1, String line2, String city, String postalCode,
                             String country, Double latitude, Double longitude) {

    public AddressDetails {
        label = required(label, "label");
        line1 = required(line1, "line1");
        line2 = line2 == null || line2.isBlank() ? null : line2.trim();
        city = required(city, "city");
        postalCode = required(postalCode, "postalCode");
        country = required(country, "country").toUpperCase(Locale.ROOT);
        if ((latitude == null) != (longitude == null)) {
            throw new InvalidAddressException("latitude and longitude must be provided together");
        }
        if (latitude != null && (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)) {
            throw new InvalidAddressException("coordinates are out of range");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidAddressException(name + " must not be blank");
        }
        return value.trim();
    }
}
