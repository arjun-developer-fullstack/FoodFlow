package com.foodflow.userservice.user;

import com.foodflow.platform.id.UuidV7;
import com.foodflow.userservice.support.AbstractIntegrationTest;
import com.foodflow.userservice.user.domain.Role;
import com.foodflow.userservice.user.domain.User;
import com.foodflow.userservice.user.domain.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Address book and administration against the real database constraints. */
class AddressAndAdminIT extends AbstractIntegrationTest {

    private static final String ADDRESS = """
            {"label":"%s","line1":"221B Baker Street","city":"London","postalCode":"NW1 6XE","country":"gb",
             "latitude":51.5237,"longitude":-0.1585,"makeDefault":%s}
            """;

    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void addressLifecycleKeepsExactlyOneDefault() throws Exception {
        Session jane = register(uniqueEmail("addr"), "Address User");

        String homeId = createAddress(jane, "Home", false);
        String workId = createAddress(jane, "Work", true);

        mvc.perform(get("/api/v1/users/me/addresses").header(HttpHeaders.AUTHORIZATION, jane.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(workId))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[0].country").value("GB"))
                .andExpect(jsonPath("$[1].isDefault").value(false));

        mvc.perform(put("/api/v1/users/me/addresses/" + homeId).header(HttpHeaders.AUTHORIZATION, jane.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(ADDRESS.formatted("Home sweet home", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));

        mvc.perform(delete("/api/v1/users/me/addresses/" + homeId).header(HttpHeaders.AUTHORIZATION, jane.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/users/me/addresses").header(HttpHeaders.AUTHORIZATION, jane.bearer()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].isDefault").value(true));
    }

    @Test
    void usersCannotSeeOrModifyEachOthersAddresses() throws Exception {
        Session owner = register(uniqueEmail("owner"), "Owner");
        Session intruder = register(uniqueEmail("intruder"), "Intruder");
        String addressId = createAddress(owner, "Home", false);

        mvc.perform(delete("/api/v1/users/me/addresses/" + addressId).header(HttpHeaders.AUTHORIZATION, intruder.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/users/me/addresses").header(HttpHeaders.AUTHORIZATION, intruder.bearer()))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void adminSuspensionRevokesSessionsAndBlocksSignIn() throws Exception {
        String adminEmail = uniqueEmail("admin");
        createAdmin(adminEmail);
        Session admin = login(adminEmail, PASSWORD);
        String customerEmail = uniqueEmail("suspend");
        Session customer = register(customerEmail, "To Be Suspended");
        String customerId = JsonPath.read(mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, customer.bearer()))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/v1/admin/users").param("emailPrefix", customerEmail)
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));

        mvc.perform(patch("/api/v1/admin/users/" + customerId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        Integer activeSessions = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?::uuid AND revoked_at IS NULL",
                Integer.class, customerId);
        assertThat(activeSessions).as("suspension revokes every refresh token in the database").isZero();
        mvc.perform(post("/api/v1/auth/refresh").cookie(customer.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(customerEmail, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));

        mvc.perform(get("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, customer.bearer()))
                .andExpect(status().isForbidden());
    }

    private String createAddress(Session session, String label, boolean makeDefault) throws Exception {
        String body = mvc.perform(post("/api/v1/users/me/addresses").header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(ADDRESS.formatted(label, makeDefault)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** Administrators are provisioned by operations, never via self-service; simulate that here. */
    private void createAdmin(String email) {
        transactions.executeWithoutResult(status -> users.save(User.register(UuidV7.generate(), email, "Admin",
                passwordEncoder.encode(PASSWORD), Set.of(Role.ADMIN), Instant.now())));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles r JOIN users u ON u.id = r.user_id "
                + "WHERE u.email = ? AND r.role = 'ADMIN'", Integer.class, email)).isEqualTo(1);
    }
}
