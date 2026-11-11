package com.foodflow.userservice.user.application;

import com.foodflow.platform.error.ResourceNotFoundException;
import com.foodflow.userservice.support.InMemoryAddressRepository;
import com.foodflow.userservice.support.InMemoryUserRepository;
import com.foodflow.userservice.support.MutableClock;
import com.foodflow.userservice.support.TestUsers;
import com.foodflow.userservice.user.domain.Address;
import com.foodflow.userservice.user.domain.AddressDetails;
import com.foodflow.userservice.user.domain.User;
import com.foodflow.userservice.user.domain.UserExceptions.AddressLimitExceededException;
import com.foodflow.userservice.user.domain.UserExceptions.InvalidAddressException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddressServiceTest {

    private MutableClock clock;
    private InMemoryAddressRepository addresses;
    private AddressService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingAt("2026-11-07T10:00:00Z");
        addresses = new InMemoryAddressRepository();
        InMemoryUserRepository users = new InMemoryUserRepository();
        User user = users.save(TestUsers.customer("jane@example.com", Instant.now(clock)));
        userId = user.getId();
        service = new AddressService(addresses, users, clock);
    }

    @Test
    void firstAddressBecomesDefaultAutomatically() {
        Address first = service.create(userId, details("Home"), false);

        assertThat(first.isDefaultAddress()).isTrue();
        assertThat(first.getCountry()).isEqualTo("GB");
    }

    @Test
    void makingAnotherAddressDefaultClearsThePreviousDefault() {
        Address home = service.create(userId, details("Home"), false);
        clock.advance(Duration.ofSeconds(1));
        Address work = service.create(userId, details("Work"), true);

        assertThat(work.isDefaultAddress()).isTrue();
        assertThat(home.isDefaultAddress()).isFalse();
        assertThat(addresses.defaultCount(userId)).isEqualTo(1);
        assertThat(service.list(userId)).extracting(Address::getLabel).containsExactly("Work", "Home");
    }

    @Test
    void updateCanPromoteAnAddressToDefault() {
        Address home = service.create(userId, details("Home"), false);
        Address work = service.create(userId, details("Work"), false);

        service.update(userId, work.getId(), details("Office"), true);

        assertThat(work.isDefaultAddress()).isTrue();
        assertThat(work.getLabel()).isEqualTo("Office");
        assertThat(home.isDefaultAddress()).isFalse();
    }

    @Test
    void deletingTheDefaultPromotesTheOldestRemainingAddress() {
        Address home = service.create(userId, details("Home"), false);
        clock.advance(Duration.ofSeconds(1));
        Address work = service.create(userId, details("Work"), false);
        clock.advance(Duration.ofSeconds(1));
        Address gym = service.create(userId, details("Gym"), false);

        service.delete(userId, home.getId());

        assertThat(work.isDefaultAddress()).isTrue();
        assertThat(gym.isDefaultAddress()).isFalse();
    }

    @Test
    void enforcesThePerUserLimit() {
        for (int i = 0; i < AddressService.MAX_ADDRESSES_PER_USER; i++) {
            service.create(userId, details("A" + i), false);
        }

        assertThatThrownBy(() -> service.create(userId, details("One too many"), false))
                .isInstanceOf(AddressLimitExceededException.class);
    }

    @Test
    void anotherUsersAddressIsReportedAsNotFound() {
        Address home = service.create(userId, details("Home"), false);
        UUID intruder = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(intruder, home.getId(), details("Mine now"), false))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(userId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsHalfSpecifiedCoordinates() {
        assertThatThrownBy(() -> new AddressDetails("Home", "1 Main St", null, "London", "N1", "GB", 51.5, null))
                .isInstanceOf(InvalidAddressException.class);
    }

    private static AddressDetails details(String label) {
        return new AddressDetails(label, "221B Baker Street", null, "London", "NW1 6XE", "gb", 51.5237, -0.1585);
    }
}
