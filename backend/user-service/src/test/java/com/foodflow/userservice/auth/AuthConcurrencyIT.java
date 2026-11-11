package com.foodflow.userservice.auth;

import com.foodflow.userservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Races against the real database: these are the scenarios that row locks, family locks and the
 * unique index exist for. <b>Requires Docker</b> (skipped without it).
 */
class AuthConcurrencyIT extends AbstractIntegrationTest {

    private static final int PARALLELISM = 12;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void parallelWrongPasswordsAreAllCountedSoTheLockoutCannotBeOutrun() throws Exception {
        String email = uniqueEmail("race-lock");
        register(email, "Race Lock");
        String wrong = """
                {"email":"%s","password":"definitely the wrong password"}
                """.formatted(email);

        List<Integer> statuses = inParallel(() -> mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(wrong)).andReturn().getResponse().getStatus());

        assertThat(statuses).as("no lost updates surface as 409 conflicts").doesNotContain(409, 500);
        assertThat(statuses).containsOnly(401, 423);
        assertThat(statuses).contains(423);
        String lockedUntil = jdbc.queryForObject("SELECT locked_until::text FROM users WHERE email = ?",
                String.class, email);
        assertThat(lockedUntil).as("the account ends up locked").isNotNull();
        assertThat(statuses.stream().filter(s -> s == 401).count())
                .as("at most max-failed-attempts - 1 guesses are answered with 401")
                .isLessThanOrEqualTo(4);
    }

    @Test
    void parallelRegistrationsOfOneEmailCreateExactlyOneAccount() throws Exception {
        String email = uniqueEmail("race-register");
        String body = """
                {"email":"%s","password":"%s","fullName":"Racer"}
                """.formatted(email, PASSWORD);

        List<Integer> statuses = inParallel(() -> mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses).containsOnly(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void parallelRefreshesOfOneTokenRotateOnceAndKeepTheSessionAlive() throws Exception {
        Session session = register(uniqueEmail("race-refresh"), "Race Refresh");

        List<MvcResult> results = inParallel(() -> mvc.perform(post("/api/v1/auth/refresh").cookie(session.cookie()))
                .andReturn());

        List<MvcResult> succeeded = results.stream().filter(r -> r.getResponse().getStatus() == 200).toList();
        assertThat(succeeded).as("the token is single-use").hasSize(1);
        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isIn(200, 401));
        Session rotated = Session.from(succeeded.getFirst());
        mvc.perform(post("/api/v1/auth/refresh").cookie(rotated.cookie()))
                .andExpect(status().isOk()); // concurrent duplicates within the grace window did not revoke the family
    }

    private static <T> List<T> inParallel(Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < PARALLELISM; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
