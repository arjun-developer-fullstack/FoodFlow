package com.foodflow.userservice.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A delivery address owned by a user. References its owner by id (separate aggregate), and the
 * "one default address per user" invariant is also enforced by a partial unique index.
 */
@Entity
@Table(name = "addresses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 50)
    private String label;

    @Column(nullable = false, length = 200)
    private String line1;

    @Column(length = 200)
    private String line2;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(name = "postal_code", nullable = false, length = 20)
    private String postalCode;

    /** ISO 3166-1 alpha-2. */
    @Column(nullable = false, length = 2)
    private String country;

    private Double latitude;

    private Double longitude;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    private Address(UUID id, UUID userId, AddressDetails details, boolean defaultAddress, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        apply(details);
        this.defaultAddress = defaultAddress;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Address create(UUID id, UUID userId, AddressDetails details, boolean defaultAddress, Instant now) {
        return new Address(id, userId, details, defaultAddress, now);
    }

    public void update(AddressDetails details, Instant now) {
        apply(details);
        this.updatedAt = now;
    }

    public void markDefault(Instant now) {
        this.defaultAddress = true;
        this.updatedAt = now;
    }

    public boolean isOwnedBy(UUID candidateUserId) {
        return userId.equals(candidateUserId);
    }

    private void apply(AddressDetails details) {
        Objects.requireNonNull(details, "details");
        this.label = details.label();
        this.line1 = details.line1();
        this.line2 = details.line2();
        this.city = details.city();
        this.postalCode = details.postalCode();
        this.country = details.country();
        this.latitude = details.latitude();
        this.longitude = details.longitude();
    }
}
