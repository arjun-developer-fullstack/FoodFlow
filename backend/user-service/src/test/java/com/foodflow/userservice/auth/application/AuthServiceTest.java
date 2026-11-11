package com.foodflow.userservice.auth.application;

import com.foodflow.userservice.auth.application.AuthCommands.AccountType;
import com.foodflow.userservice.auth.application.AuthCommands.AuthSession;
import com.foodflow.userservice.auth.application.AuthCommands.ClientContext;
import com.foodflow.userservice.auth.application.AuthCommands.LoginCommand;
import com.foodflow.userservice.auth.application.AuthCommands.RegisterCommand;
import com.foodflow.userservice.auth.domain.AuthExceptions.AccountLockedException;
import com.foodflow.userservice.auth.domain.AuthExceptions.AccountSuspendedException;
import com.foodflow.userservice.auth.domain.AuthExceptions.InvalidCredentialsException;
import com.foodflow.userservice.auth.domain.AuthExceptions.InvalidRefreshTokenException;
import com.foodflow.userservice.auth.domain.RefreshToken;
import com.foodflow.userservice.support.FakePasswordEncoder;
import com.foodflow.userservice.support.InMemoryRefreshTokenRepository;
import com.foodflow.userservice.support.InMemoryUserRepository;
import com.foodflow.userservice.support.MutableClock;
import com.foodflow.userservice.support.NoOpTransactionManager;
import com.foodflow.userservice.support.TestUsers;
import com.foodflow.userservice.user.application.UserEventPublisher;
import com.foodflow.userservice.user.domain.Role;
import com.foodflow.userservice.user.domain.User;
import com.foodflow.userservice.user.domain.UserExceptions.EmailAlreadyRegisteredException;
import com.foodflow.userservice.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {

    private static final ClientContext CLIENT = new ClientContext("JUnit", "203.0.113.7");

    private MutableClock clock;
    private InMemoryUserRepository users;
    private InMemoryRefreshTokenRepository refreshTokens;
    private FakePasswordEncoder passwordEncoder;
    private List<User> publishedRegistrations;
    private AuthService service;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingAt("2026-11-07T10:00:00Z");
        users = new InMemoryUserRepository();
        refreshTokens = new InMemoryRefreshTokenRepository();
        passwordEncoder = new FakePasswordEncoder();
        publishedRegistrations = new ArrayList<>();
        UserEventPublisher events = publishedRegistrations::add;
        AccessTokenIssuer accessTokens = user -> new AccessTokenIssuer.IssuedAccessToken(
                "access-token-for-" + user.getId(), Instant.now(clock).plus(Duration.ofMinutes(15)));
        AuthProperties properties = new AuthProperties(Duration.ofMinutes(15), Duration.ofDays(14), Duration.ofSeconds(10),
                new AuthProperties.Lockout(3, Duration.ofMinutes(15)),
                new AuthProperties.PasswordHashing(2, Duration.ofMillis(100)));
        transactions = new NoOpTransactionManager();
        passwordEncoder.forbidWhen(transactions::inTransaction);
        service = new AuthService(users, refreshTokens, new PasswordHasher(passwordEncoder, 2, Duration.ofMillis(100)),
                accessTokens, events, properties, transactions, clock);
    }

    private NoOpTransactionManager transactions;

    @Nested
    class Register {

        @Test
        void createsCustomerHashesPasswordPublishesEventAndStartsSession() {
            AuthSession session = service.register(
                    new RegisterCommand("  Jane.Doe@Example.COM ", TestUsers.PASSWORD, " Jane Doe ", AccountType.CUSTOMER),
                    CLIENT);

            User user = users.findById(session.userId()).orElseThrow();
            assertThat(user.getEmail()).isEqualTo("jane.doe@example.com");
            assertThat(user.getFullName()).isEqualTo("Jane Doe");
            assertThat(user.getRoles()).containsExactly(Role.CUSTOMER);
            assertThat(user.passwordHash()).isEqualTo("hashed:" + TestUsers.PASSWORD).doesNotContain("battery staple ");
            assertThat(publishedRegistrations).containsExactly(user);
            assertThat(session.accessToken()).isEqualTo("access-token-for-" + user.getId());
            assertThat(session.refreshToken()).hasSize(43);
            assertThat(session.refreshTokenExpiresAt()).isEqualTo(Instant.now(clock).plus(Duration.ofDays(14)));
        }

        @Test
        void storesOnlyTheHashOfTheRefreshToken() {
            AuthSession session = service.register(
                    new RegisterCommand("jane@example.com", TestUsers.PASSWORD, "Jane", AccountType.CUSTOMER), CLIENT);

            RefreshToken stored = refreshTokens.all().getFirst();
            assertThat(stored.getTokenHash()).isEqualTo(OpaqueTokens.hash(session.refreshToken()))
                    .isNotEqualTo(session.refreshToken());
            assertThat(stored.getUserAgent()).isEqualTo("JUnit");
            assertThat(stored.getIpAddress()).isEqualTo("203.0.113.7");
        }

        @Test
        void grantsTheRequestedSelfServiceRole() {
            AuthSession session = service.register(
                    new RegisterCommand("rider@example.com", TestUsers.PASSWORD, "Rider", AccountType.COURIER), CLIENT);

            assertThat(users.findById(session.userId()).orElseThrow().getRoles()).containsExactly(Role.COURIER);
        }

        @Test
        void rejectsAnAlreadyRegisteredEmailIgnoringCase() {
            users.save(TestUsers.customer("jane@example.com", Instant.now(clock)));

            assertThatThrownBy(() -> service.register(
                    new RegisterCommand("JANE@example.com", TestUsers.PASSWORD, "Jane", AccountType.CUSTOMER), CLIENT))
                    .isInstanceOf(EmailAlreadyRegisteredException.class);
            assertThat(publishedRegistrations).isEmpty();
        }
    }

    @Nested
    class Login {

        private User user;

        @BeforeEach
        void existingUser() {
            user = users.save(TestUsers.customer("jane@example.com", Instant.now(clock)));
        }

        @Test
        void succeedsWithCorrectPasswordAndRecordsTheLogin() {
            AuthSession session = service.login(new LoginCommand("Jane@Example.com", TestUsers.PASSWORD), CLIENT);

            assertThat(session.userId()).isEqualTo(user.getId());
            assertThat(user.getLastLoginAt()).isEqualTo(Instant.now(clock));
        }

        @Test
        void unknownEmailFailsLikeAWrongPasswordAndStillVerifiesAHash() {
            int before = passwordEncoder.matchCalls();

            assertThatThrownBy(() -> service.login(new LoginCommand("nobody@example.com", TestUsers.PASSWORD), CLIENT))
                    .isInstanceOf(InvalidCredentialsException.class);
            assertThat(passwordEncoder.matchCalls()).as("timing equalization").isEqualTo(before + 1);
        }

        @Test
        void locksTheAccountAfterTooManyFailuresEvenForTheCorrectPassword() {
            LoginCommand wrong = new LoginCommand("jane@example.com", "wrong password!!");
            assertThatThrownBy(() -> service.login(wrong, CLIENT)).isInstanceOf(InvalidCredentialsException.class);
            assertThatThrownBy(() -> service.login(wrong, CLIENT)).isInstanceOf(InvalidCredentialsException.class);
            assertThatThrownBy(() -> service.login(wrong, CLIENT)).isInstanceOf(AccountLockedException.class);

            assertThatThrownBy(() -> service.login(new LoginCommand("jane@example.com", TestUsers.PASSWORD), CLIENT))
                    .isInstanceOf(AccountLockedException.class);
        }

        @Test
        void theLockoutStatesHowLongItLastsSoClientsCanSendRetryAfter() {
            LoginCommand wrong = new LoginCommand("jane@example.com", "wrong password!!");
            assertThatThrownBy(() -> service.login(wrong, CLIENT)).isInstanceOf(InvalidCredentialsException.class);
            assertThatThrownBy(() -> service.login(wrong, CLIENT)).isInstanceOf(InvalidCredentialsException.class);

            AccountLockedException lockedNow = catchLocked(() -> service.login(wrong, CLIENT));
            assertThat(lockedNow.retryAfter()).as("the attempt that locks the account").contains(Duration.ofMinutes(15));

            clock.advance(Duration.ofMinutes(4));
            AccountLockedException stillLocked =
                    catchLocked(() -> service.login(new LoginCommand("jane@example.com", TestUsers.PASSWORD), CLIENT));
            assertThat(stillLocked.retryAfter()).as("later attempts get the remaining time").contains(Duration.ofMinutes(11));
        }

        private AccountLockedException catchLocked(Runnable attempt) {
            try {
                attempt.run();
            } catch (AccountLockedException e) {
                return e;
            }
            throw new AssertionError("expected AccountLockedException");
        }

        @Test
        void lockExpiresAfterTheLockDuration() {
            LoginCommand wrong = new LoginCommand("jane@example.com", "wrong password!!");
            for (int i = 0; i < 3; i++) {
                try {
                    service.login(wrong, CLIENT);
                } catch (InvalidCredentialsException | AccountLockedException expected) {
                    // counting failures
                }
            }
            clock.advance(Duration.ofMinutes(15));

            assertThat(service.login(new LoginCommand("jane@example.com", TestUsers.PASSWORD), CLIENT).userId())
                    .isEqualTo(user.getId());
            assertThat(user.getFailedLoginAttempts()).isZero();
        }

        @Test
        void suspendedAccountIsRevealedOnlyToTheHolderOfTheCorrectPassword() {
            user.changeStatus(UserStatus.SUSPENDED, Instant.now(clock));

            assertThatThrownBy(() -> service.login(new LoginCommand("jane@example.com", "wrong password!!"), CLIENT))
                    .isInstanceOf(InvalidCredentialsException.class);
            assertThatThrownBy(() -> service.login(new LoginCommand("jane@example.com", TestUsers.PASSWORD), CLIENT))
                    .isInstanceOf(AccountSuspendedException.class);
        }

        @Test
        void upgradesOutdatedPasswordHashesOnSuccessfulLogin() {
            User legacy = users.save(com.foodflow.userservice.user.domain.User.register(
                    com.foodflow.platform.id.UuidV7.generate(), "legacy@example.com", "Legacy",
                    "legacy:" + TestUsers.PASSWORD, java.util.Set.of(Role.CUSTOMER), Instant.now(clock)));

            service.login(new LoginCommand("legacy@example.com", TestUsers.PASSWORD), CLIENT);

            assertThat(legacy.passwordHash()).isEqualTo("hashed:" + TestUsers.PASSWORD);
        }

        @Test
        void eachFailureIsRecordedInItsOwnShortTransactionAndHashingNeverRunsInsideOne() {
            int before = transactions.transactionsBegun();

            assertThatThrownBy(() -> service.login(new LoginCommand("jane@example.com", "wrong password!!"), CLIENT))
                    .isInstanceOf(InvalidCredentialsException.class);

            // read snapshot + failure write; the guard in FakePasswordEncoder proves no hash ran inside either
            assertThat(transactions.transactionsBegun() - before).isEqualTo(2);
            assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        }

        @Test
        void saturatedPasswordHashingIsRejectedAsRetryable() throws Exception {
            PasswordHasher hasher = new PasswordHasher(new FakePasswordEncoder() {
                @Override
                public boolean matches(CharSequence raw, String encoded) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return super.matches(raw, encoded);
                }
            }, 1, Duration.ofMillis(50));
            Thread slow = Thread.ofVirtual().start(() -> hasher.matches("x", "hashed:x"));
            Thread.sleep(100);

            assertThatThrownBy(() -> hasher.matches("x", "hashed:x"))
                    .isInstanceOf(com.foodflow.userservice.auth.domain.AuthExceptions.AuthenticationBusyException.class);
            slow.join();
            assertThat(hasher.matches("x", "hashed:x")).as("capacity is released afterwards").isTrue();
        }
    }

    @Nested
    class RefreshAndLogout {

        private AuthSession initial;

        @BeforeEach
        void signedIn() {
            initial = service.register(
                    new RegisterCommand("jane@example.com", TestUsers.PASSWORD, "Jane", AccountType.CUSTOMER), CLIENT);
        }

        @Test
        void rotatesTheRefreshTokenWithinTheSameFamily() {
            AuthSession refreshed = service.refresh(initial.refreshToken(), CLIENT);

            assertThat(refreshed.refreshToken()).isNotEqualTo(initial.refreshToken());
            RefreshToken old = token(initial.refreshToken());
            RefreshToken successor = token(refreshed.refreshToken());
            assertThat(old.isRevoked()).isTrue();
            assertThat(old.getReplacedBy()).isEqualTo(successor.getId());
            assertThat(successor.getFamilyId()).isEqualTo(old.getFamilyId());
            assertThat(successor.isRevoked()).isFalse();
        }

        @Test
        void reusingARotatedTokenRevokesTheWholeFamily() {
            AuthSession refreshed = service.refresh(initial.refreshToken(), CLIENT);
            clock.advance(Duration.ofSeconds(11)); // beyond the benign-race grace window

            assertThatThrownBy(() -> service.refresh(initial.refreshToken(), CLIENT))
                    .isInstanceOf(InvalidRefreshTokenException.class);
            assertThatThrownBy(() -> service.refresh(refreshed.refreshToken(), CLIENT))
                    .as("the attacker's or victim's newer token is revoked too")
                    .isInstanceOf(InvalidRefreshTokenException.class);
        }

        @Test
        void aReplayWithinTheGraceWindowIsRejectedWithoutEndingTheSession() {
            AuthSession refreshed = service.refresh(initial.refreshToken(), CLIENT);
            clock.advance(Duration.ofSeconds(3)); // e.g. a second tab refreshing with the old cookie

            assertThatThrownBy(() -> service.refresh(initial.refreshToken(), CLIENT))
                    .isInstanceOfSatisfying(InvalidRefreshTokenException.class, e -> assertThat(e.keepsCookie())
                            .as("the browser may already hold the successor cookie: do not clear it")
                            .isTrue());
            assertThat(service.refresh(refreshed.refreshToken(), CLIENT).userId())
                    .as("the legitimate successor session survives")
                    .isEqualTo(initial.userId());
        }

        @Test
        void onlyTheBenignRaceKeepsTheCookieEveryOtherRejectionClearsIt() {
            service.refresh(initial.refreshToken(), CLIENT);
            clock.advance(Duration.ofSeconds(11)); // beyond the grace window: reuse

            assertThatThrownBy(() -> service.refresh(initial.refreshToken(), CLIENT))
                    .as("reuse").isInstanceOfSatisfying(InvalidRefreshTokenException.class,
                            e -> assertThat(e.keepsCookie()).isFalse());
            assertThatThrownBy(() -> service.refresh(OpaqueTokens.generate(), CLIENT))
                    .as("unknown").isInstanceOfSatisfying(InvalidRefreshTokenException.class,
                            e -> assertThat(e.keepsCookie()).isFalse());
            assertThatThrownBy(() -> service.refresh("garbage", CLIENT))
                    .as("malformed").isInstanceOfSatisfying(InvalidRefreshTokenException.class,
                            e -> assertThat(e.keepsCookie()).isFalse());
        }

        @Test
        void aTokenRevokedBySignOutClearsTheCookieEvenInsideTheGraceWindow() {
            AuthSession refreshed = service.refresh(initial.refreshToken(), CLIENT);
            service.logout(refreshed.refreshToken());

            assertThatThrownBy(() -> service.refresh(refreshed.refreshToken(), CLIENT))
                    .as("successor revoked by sign-out, never rotated")
                    .isInstanceOfSatisfying(InvalidRefreshTokenException.class, e -> assertThat(e.keepsCookie()).isFalse());
        }

        @Test
        void otherSessionsOfTheSameUserSurviveAReuseIncident() {
            AuthSession otherDevice = service.login(new LoginCommand("jane@example.com", TestUsers.PASSWORD), CLIENT);
            service.refresh(initial.refreshToken(), CLIENT);
            clock.advance(Duration.ofSeconds(11));
            assertThatThrownBy(() -> service.refresh(initial.refreshToken(), CLIENT))
                    .isInstanceOf(InvalidRefreshTokenException.class);

            assertThat(service.refresh(otherDevice.refreshToken(), CLIENT).userId()).isEqualTo(initial.userId());
        }

        @Test
        void rejectsExpiredTokens() {
            clock.advance(Duration.ofDays(14));

            assertThatThrownBy(() -> service.refresh(initial.refreshToken(), CLIENT))
                    .isInstanceOf(InvalidRefreshTokenException.class);
        }

        @Test
        void rejectsMalformedAndUnknownTokens() {
            assertThatThrownBy(() -> service.refresh("garbage", CLIENT)).isInstanceOf(InvalidRefreshTokenException.class);
            assertThatThrownBy(() -> service.refresh(OpaqueTokens.generate(), CLIENT))
                    .isInstanceOf(InvalidRefreshTokenException.class);
        }

        @Test
        void suspendedUsersCannotRefreshAndTheirFamilyIsRevoked() {
            users.findById(initial.userId()).orElseThrow().changeStatus(UserStatus.SUSPENDED, Instant.now(clock));

            assertThatThrownBy(() -> service.refresh(initial.refreshToken(), CLIENT))
                    .isInstanceOf(InvalidRefreshTokenException.class);
            assertThat(token(initial.refreshToken()).isRevoked()).isTrue();
        }

        @Test
        void logoutRevokesTheFamilyAndIsIdempotent() {
            AuthSession refreshed = service.refresh(initial.refreshToken(), CLIENT);

            service.logout(refreshed.refreshToken());
            service.logout(refreshed.refreshToken());
            service.logout("not-a-token");

            assertThatThrownBy(() -> service.refresh(refreshed.refreshToken(), CLIENT))
                    .isInstanceOf(InvalidRefreshTokenException.class);
        }

        private RefreshToken token(String raw) {
            return refreshTokens.findByTokenHashForUpdate(OpaqueTokens.hash(raw)).orElseThrow();
        }
    }
}
