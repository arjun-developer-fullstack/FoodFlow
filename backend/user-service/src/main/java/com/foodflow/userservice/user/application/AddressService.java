package com.foodflow.userservice.user.application;

import com.foodflow.platform.error.ResourceNotFoundException;
import com.foodflow.platform.id.UuidV7;
import com.foodflow.userservice.user.domain.Address;
import com.foodflow.userservice.user.domain.AddressDetails;
import com.foodflow.userservice.user.domain.AddressRepository;
import com.foodflow.userservice.user.domain.UserExceptions.AddressLimitExceededException;
import com.foodflow.userservice.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Address book of the authenticated user.
 *
 * <p>Invariants: at most {@value #MAX_ADDRESSES_PER_USER} addresses; exactly one default while
 * any address exists. Every mutation first locks the owning user row, so concurrent requests of the
 * same user are serialized and cannot break these invariants.
 */
@Service
public class AddressService {

    public static final int MAX_ADDRESSES_PER_USER = 20;

    private final AddressRepository addresses;
    private final UserRepository users;
    private final Clock clock;

    public AddressService(AddressRepository addresses, UserRepository users, Clock clock) {
        this.addresses = addresses;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Address> list(UUID userId) {
        return addresses.findAllByUserId(userId).stream()
                .sorted(Comparator.comparing(Address::isDefaultAddress).reversed()
                        .thenComparing(Address::getCreatedAt))
                .toList();
    }

    @Transactional
    public Address create(UUID userId, AddressDetails details, boolean makeDefault) {
        lockOwner(userId);
        long existing = addresses.countByUserId(userId);
        if (existing >= MAX_ADDRESSES_PER_USER) {
            throw new AddressLimitExceededException(MAX_ADDRESSES_PER_USER);
        }
        boolean isDefault = makeDefault || existing == 0;
        Instant now = Instant.now(clock);
        if (isDefault) {
            addresses.clearDefaultExcept(userId, null, now);
        }
        return addresses.save(Address.create(UuidV7.generate(clock), userId, details, isDefault, now));
    }

    @Transactional
    public Address update(UUID userId, UUID addressId, AddressDetails details, boolean makeDefault) {
        lockOwner(userId);
        Address address = findOwned(userId, addressId);
        Instant now = Instant.now(clock);
        if (makeDefault && !address.isDefaultAddress()) {
            // clear first: the partial unique index allows only one default per user
            addresses.clearDefaultExcept(userId, addressId, now);
            address.markDefault(now);
        }
        address.update(details, now);
        return addresses.save(address);
    }

    @Transactional
    public void delete(UUID userId, UUID addressId) {
        lockOwner(userId);
        Address address = findOwned(userId, addressId);
        boolean wasDefault = address.isDefaultAddress();
        addresses.delete(address);
        if (wasDefault) {
            addresses.findAllByUserId(userId).stream()
                    .filter(candidate -> !candidate.getId().equals(addressId))
                    .min(Comparator.comparing(Address::getCreatedAt))
                    .ifPresent(successor -> successor.markDefault(Instant.now(clock)));
        }
    }

    private Address findOwned(UUID userId, UUID addressId) {
        // Another user's address is reported as "not found" so its existence is not disclosed.
        return addresses.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Address"));
    }

    private void lockOwner(UUID userId) {
        users.findByIdForUpdate(userId).orElseThrow(() -> new ResourceNotFoundException("User"));
    }
}
