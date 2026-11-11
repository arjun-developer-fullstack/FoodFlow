package com.foodflow.userservice.support;

import com.foodflow.userservice.user.domain.Address;
import com.foodflow.userservice.user.domain.AddressRepository;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryAddressRepository implements AddressRepository {

    private final Map<UUID, Address> addresses = new LinkedHashMap<>();

    @Override
    public Address save(Address address) {
        addresses.put(address.getId(), address);
        return address;
    }

    @Override
    public void delete(Address address) {
        addresses.remove(address.getId());
    }

    @Override
    public List<Address> findAllByUserId(UUID userId) {
        return addresses.values().stream().filter(a -> a.getUserId().equals(userId)).toList();
    }

    @Override
    public Optional<Address> findByIdAndUserId(UUID id, UUID userId) {
        return Optional.ofNullable(addresses.get(id)).filter(a -> a.getUserId().equals(userId));
    }

    @Override
    public long countByUserId(UUID userId) {
        return findAllByUserId(userId).size();
    }

    /** Mirrors the JPQL bulk update, which bypasses entity methods. */
    @Override
    public void clearDefaultExcept(UUID userId, UUID keepId, Instant now) {
        findAllByUserId(userId).stream()
                .filter(a -> keepId == null || !a.getId().equals(keepId))
                .forEach(a -> setDefaultFlag(a, false));
    }

    public long defaultCount(UUID userId) {
        return findAllByUserId(userId).stream().filter(Address::isDefaultAddress).count();
    }

    private static void setDefaultFlag(Address address, boolean value) {
        try {
            Field field = Address.class.getDeclaredField("defaultAddress");
            field.setAccessible(true);
            field.setBoolean(address, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
