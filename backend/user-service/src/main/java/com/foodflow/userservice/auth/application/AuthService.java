package com.foodflow.userservice.auth.application;

import com.foodflow.platform.error.DomainException;
import com.foodflow.platform.id.UuidV7;
import com.foodflow.userservice.auth.application.AccessTokenIssuer.IssuedAccessToken;
import com.foodflow.userservice.auth.application.AuthCommands.AuthSession;
import com.foodflow.userservice.auth.application.AuthCommands.ClientContext;
import com.foodflow.userservice.auth.application.AuthCommands.LoginCommand;
import com.foodflow.userservice.auth.application.AuthCommands.RegisterCommand;
import com.foodflow.userservice.auth.domain.AuthExceptions.AccountLockedException;
import com.foodflow.userservice.auth.domain.AuthExceptions.AccountSuspendedException;
import com.foodflow.userservice.auth.domain.AuthExceptions.InvalidCredentialsException;
import com.foodflow.userservice.auth.domain.AuthExceptions.InvalidRefreshTokenException;
import com.foodflow.userservice.auth.domain.RefreshToken;
import com.foodflow.userservice.auth.domain.RefreshTokenRepository;
import com.foodflow.userservice.user.application.UserEventPublisher;
import com.foodflow.userservice.user.domain.EmailAddress;
import com.foodflow.userservice.user.domain.LockoutPolicy;
import com.foodflow.userservice.user.domain.User;
import com.foodflow.userservice.user.domain.UserExceptions.EmailAlreadyRegisteredException;
import com.foodflow.userservice.user.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Registration, sign-in, token refresh (rotation with reuse detection) and sign-out (ADR-0007).
 *
 * <p><b>Transactions are short and never span password hashing.</b> Sign-in reads a snapshot,
 * verifies the password outside any transaction (bounded by {@link PasswordHasher}), then records
 * the outcome in a short transaction that holds the user's row lock. This keeps pooled connections
 * free during the expensive hash and makes failure counting race-free: concurrent wrong guesses
 * are serialized on the row, so each one is counted and the lockout cannot be outrun.
 *
 * <p>Security properties:
 * <ul>
 *   <li>Unknown e-mail and wrong password produce the same error; an unknown e-mail still costs one
 *       hash verification. (Registration and the lockout response do reveal that an e-mail is
 *       registered; see the accepted-risk note in docs/architecture/ARCHITECTURE.md.)</li>
 *   <li>Lock and suspension are re-checked under the row lock before a session is issued.</li>
 *   <li>Refresh tokens are single-use. Replaying a consumed token revokes the entire family, except
 *       within a short grace window that absorbs benign races (two tabs refreshing at once).</li>
 *   <li>Rotation, reuse revocation and sign-out of one family are serialized by a family lock.</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher passwords;
    private final AccessTokenIssuer accessTokens;
    private final UserEventPublisher events;
    private final AuthProperties properties;
    private final LockoutPolicy lockoutPolicy;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;
    /** Hash of a random secret; verified against when the e-mail is unknown (timing equalization). */
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordHasher passwords,
                       AccessTokenIssuer accessTokens, UserEventPublisher events, AuthProperties properties,
                       PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.accessTokens = accessTokens;
        this.events = events;
        this.properties = properties;
        this.lockoutPolicy = new LockoutPolicy(properties.lockout().maxFailedAttempts(),
                properties.lockout().duration());
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
        this.dummyPasswordHash = passwords.hash(OpaqueTokens.generate());
    }

    // ------------------------------------------------------------------ registration

    public AuthSession register(RegisterCommand command, ClientContext client) {
        String email = EmailAddress.normalize(command.email());
        if (Boolean.TRUE.equals(readTransaction.execute(status -> users.existsByEmail(email)))) {
            throw new EmailAlreadyRegisteredException(); // cheap rejection before paying for a hash
        }
        String passwordHash = passwords.hash(command.password());
        try {
            return inTransaction(() -> {
                if (users.existsByEmail(email)) {
                    throw new EmailAlreadyRegisteredException();
                }
                Instant now = Instant.now(clock);
                User user = User.register(UuidV7.generate(clock), email, command.fullName(), passwordHash,
                        Set.of(command.accountType().role()), now);
                users.save(user);
                events.userRegistered(user);
                user.recordSuccessfulLogin(now);
                log.info("User {} registered as {}", user.getId(), command.accountType());
                return issueSession(user, UuidV7.generate(clock), UuidV7.generate(clock), client, now);
            });
        } catch (DataIntegrityViolationException e) {
            // Two concurrent registrations of the same e-mail: the unique index decided.
            throw new EmailAlreadyRegisteredException();
        }
    }

    // ------------------------------------------------------------------ sign-in

    public AuthSession login(LoginCommand command, ClientContext client) {
        String email = EmailAddress.normalize(command.email());
        LoginCandidate candidate = readTransaction.execute(status ->
                users.findByEmail(email).map(LoginCandidate::of).orElse(null));

        if (candidate == null) {
            passwords.matches(command.password(), dummyPasswordHash);
            throw new InvalidCredentialsException();
        }
        Instant checkedAt = Instant.now(clock);
        if (candidate.lockedAt(checkedAt)) {
            securityLog.warn("Sign-in rejected for locked user {} from {}", candidate.id(), client.ipAddress());
            throw new AccountLockedException(Duration.between(checkedAt, candidate.lockedUntil()));
        }
        if (!passwords.matches(command.password(), candidate.passwordHash())) {
            throw recordFailedAttempt(candidate.id(), client);
        }
        String upgradedHash = passwords.needsUpgrade(candidate.passwordHash()) ? passwords.hash(command.password()) : null;

        return inTransaction(() -> {
            Instant now = Instant.now(clock);
            User user = users.findByIdForUpdate(candidate.id()).orElseThrow(InvalidCredentialsException::new);
            if (!user.passwordHash().equals(candidate.passwordHash())) {
                throw new InvalidCredentialsException(); // password changed while we were verifying
            }
            if (user.isLockedAt(now)) {
                // concurrent failures locked the account meanwhile
                throw new AccountLockedException(Duration.between(now, user.getLockedUntil()));
            }
            // Status is checked only after a correct password, so it is not disclosed to others.
            if (!user.isActive()) {
                throw new AccountSuspendedException();
            }
            if (upgradedHash != null) {
                user.rehashPassword(upgradedHash, now);
            }
            user.recordSuccessfulLogin(now);
            users.save(user);
            return issueSession(user, UuidV7.generate(clock), UuidV7.generate(clock), client, now);
        });
    }

    /**
     * Counts a failed attempt under the row lock and commits it.
     *
     * @return the exception to throw to the caller (invalid credentials, or locked)
     */
    private DomainException recordFailedAttempt(UUID userId, ClientContext client) {
        Optional<Duration> lockRemaining = inTransaction(() -> {
            Instant now = Instant.now(clock);
            User user = users.findByIdForUpdate(userId).orElseThrow(InvalidCredentialsException::new);
            if (user.isLockedAt(now)) {
                return Optional.of(Duration.between(now, user.getLockedUntil()));
            }
            boolean lockedNow = user.recordFailedLogin(now, lockoutPolicy);
            users.save(user);
            if (lockedNow) {
                securityLog.warn("User {} locked after {} failed sign-in attempts (last from {})",
                        userId, lockoutPolicy.maxFailedAttempts(), client.ipAddress());
                return Optional.of(Duration.between(now, user.getLockedUntil()));
            }
            return Optional.<Duration>empty();
        });
        return lockRemaining.<DomainException>map(AccountLockedException::new).orElseGet(InvalidCredentialsException::new);
    }

    // ------------------------------------------------------------------ refresh & sign-out

    public AuthSession refresh(String presentedToken, ClientContext client) {
        if (!OpaqueTokens.looksValid(presentedToken)) {
            throw new InvalidRefreshTokenException();
        }
        String tokenHash = OpaqueTokens.hash(presentedToken);
        RefreshOutcome outcome = inTransaction(() -> rotate(tokenHash, client));
        if (outcome.supersededWithinGrace()) {
            throw InvalidRefreshTokenException.supersededWithinGrace(); // session intact: keep the cookie
        }
        if (outcome.session() == null) {
            throw new InvalidRefreshTokenException(); // revocations (if any) were committed
        }
        return outcome.session();
    }

    private RefreshOutcome rotate(String tokenHash, ClientContext client) {
        UUID familyId = refreshTokens.findFamilyIdByTokenHash(tokenHash).orElse(null);
        if (familyId == null) {
            return RefreshOutcome.REJECTED;
        }
        refreshTokens.lockFamily(familyId);
        RefreshToken current = refreshTokens.findByTokenHashForUpdate(tokenHash).orElse(null);
        Instant now = Instant.now(clock);
        if (current == null) {
            return RefreshOutcome.REJECTED;
        }
        if (current.isRevoked()) {
            if (current.wasRotated() && rotatedWithinGrace(current, now)) {
                return RefreshOutcome.SUPERSEDED; // benign race: reject, but leave the session alone
            }
            if (current.wasRotated()) {
                int revoked = refreshTokens.revokeFamily(familyId, now);
                securityLog.warn("Refresh token reuse detected for user {} (family {}, {} sessions revoked, from {})",
                        current.getUserId(), familyId, revoked, client.ipAddress());
            }
            return RefreshOutcome.REJECTED;
        }
        if (current.isExpiredAt(now)) {
            return RefreshOutcome.REJECTED;
        }
        User user = users.findById(current.getUserId()).orElse(null);
        if (user == null || !user.canAuthenticateAt(now)) {
            refreshTokens.revokeFamily(familyId, now);
            return RefreshOutcome.REJECTED;
        }
        UUID successorId = UuidV7.generate(clock);
        current.rotateTo(successorId, now);
        refreshTokens.save(current);
        return new RefreshOutcome(issueSession(user, familyId, successorId, client, now));
    }

    private boolean rotatedWithinGrace(RefreshToken token, Instant now) {
        return token.getRevokedAt() != null
                && !now.isAfter(token.getRevokedAt().plus(properties.refreshReuseGrace()));
    }

    /** Ends the session family of the presented token. Idempotent: unknown tokens are ignored. */
    public void logout(String presentedToken) {
        if (!OpaqueTokens.looksValid(presentedToken)) {
            return;
        }
        String tokenHash = OpaqueTokens.hash(presentedToken);
        writeTransaction.executeWithoutResult(status ->
                refreshTokens.findFamilyIdByTokenHash(tokenHash).ifPresent(familyId -> {
                    refreshTokens.lockFamily(familyId);
                    refreshTokens.revokeFamily(familyId, Instant.now(clock));
                }));
    }

    // ------------------------------------------------------------------ helpers

    private AuthSession issueSession(User user, UUID familyId, UUID tokenId, ClientContext client, Instant now) {
        String rawRefreshToken = OpaqueTokens.generate();
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        refreshTokens.save(RefreshToken.issue(tokenId, user.getId(), familyId, OpaqueTokens.hash(rawRefreshToken),
                now, refreshExpiresAt, client.userAgent(), client.ipAddress()));
        IssuedAccessToken accessToken = accessTokens.issue(user);
        return new AuthSession(user.getId(), accessToken.value(), accessToken.expiresAt(), rawRefreshToken,
                refreshExpiresAt);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(writeTransaction.execute(status -> work.get()));
    }

    /** Immutable credentials snapshot, so no entity (or connection) is held during verification. */
    private record LoginCandidate(UUID id, String passwordHash, Instant lockedUntil) {

        static LoginCandidate of(User user) {
            return new LoginCandidate(user.getId(), user.passwordHash(), user.getLockedUntil());
        }

        boolean lockedAt(Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }
    }

    /** {@code session} on success; otherwise rejected, possibly as a benign race inside the grace window. */
    private record RefreshOutcome(AuthSession session, boolean supersededWithinGrace) {
        static final RefreshOutcome REJECTED = new RefreshOutcome(null, false);
        static final RefreshOutcome SUPERSEDED = new RefreshOutcome(null, true);

        RefreshOutcome(AuthSession session) {
            this(session, false);
        }
    }
}
