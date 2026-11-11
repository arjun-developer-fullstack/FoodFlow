package com.foodflow.userservice.support;

import com.foodflow.userservice.auth.domain.RefreshToken;
import com.foodflow.userservice.auth.domain.RefreshTokenRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class InMemoryRefreshTokenRepository implements RefreshTokenRepository {

    private final Map<UUID, RefreshToken> tokens = new LinkedHashMap<>();

    @Override
    public RefreshToken save(RefreshToken token) {
        tokens.put(token.getId(), token);
        return token;
    }

    @Override
    public Optional<UUID> findFamilyIdByTokenHash(String tokenHash) {
        return findByTokenHashForUpdate(tokenHash).map(RefreshToken::getFamilyId);
    }

    @Override
    public void lockFamily(UUID familyId) {
        // single-threaded tests: nothing to serialize
    }

    @Override
    public Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash) {
        return tokens.values().stream().filter(t -> t.getTokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public int revokeFamily(UUID familyId, Instant now) {
        return revokeWhere(t -> t.getFamilyId().equals(familyId), now);
    }

    @Override
    public int revokeAllForUser(UUID userId, Instant now) {
        return revokeWhere(t -> t.getUserId().equals(userId), now);
    }

    public List<RefreshToken> all() {
        return new ArrayList<>(tokens.values());
    }

    private int revokeWhere(java.util.function.Predicate<RefreshToken> filter, Instant now) {
        int count = 0;
        for (RefreshToken token : tokens.values()) {
            if (filter.test(token) && !token.isRevoked()) {
                token.revoke(now);
                count++;
            }
        }
        return count;
    }
}
