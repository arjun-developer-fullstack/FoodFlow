package com.foodflow.userservice.user.api;

import com.foodflow.platform.error.ResourceNotFoundException;
import com.foodflow.userservice.common.config.SecurityConfiguration;
import com.foodflow.userservice.support.TestUsers;
import com.foodflow.userservice.support.WebSliceTestSupport;
import com.foodflow.userservice.user.application.AddressService;
import com.foodflow.userservice.user.application.AdminUserService;
import com.foodflow.userservice.user.application.UserProfileService;
import com.foodflow.userservice.user.domain.Address;
import com.foodflow.userservice.user.domain.AddressDetails;
import com.foodflow.userservice.user.domain.User;
import com.foodflow.userservice.user.domain.UserExceptions.SelfStatusChangeException;
import com.foodflow.userservice.user.domain.UserStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ProfileController.class, AddressController.class, AdminUserController.class})
@Import(SecurityConfiguration.class)
class UserControllersTest extends WebSliceTestSupport {

    private static final String ADDRESS_JSON = """
            {"label":"Home","line1":"221B Baker Street","city":"London","postalCode":"NW1 6XE","country":"GB",
             "latitude":51.5237,"longitude":-0.1585}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserProfileService profiles;

    @MockitoBean
    private AddressService addresses;

    @MockitoBean
    private AdminUserService adminUsers;

    private final User jane = TestUsers.customer("jane@example.com", Instant.parse("2026-11-07T10:00:00Z"));

    @Nested
    class Profile {

        @Test
        void requiresAnAccessToken() throws Exception {
            mvc.perform(get("/api/v1/users/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            verifyNoInteractions(profiles);
        }

        @Test
        void returnsTheCallersOwnProfile() throws Exception {
            given(profiles.getProfile(jane.getId())).willReturn(jane);

            mvc.perform(get("/api/v1/users/me").with(userJwt(jane.getId(), "CUSTOMER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(jane.getId().toString()))
                    .andExpect(jsonPath("$.email").value("jane@example.com"))
                    .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());
        }

        @Test
        void rejectsInvalidPhoneNumbers() throws Exception {
            mvc.perform(patch("/api/v1/users/me").with(userJwt(jane.getId(), "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"12345\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("phone"));
        }

        @Test
        void acceptsAPartialUpdate() throws Exception {
            given(profiles.updateProfile(eq(jane.getId()), any())).willReturn(jane);

            mvc.perform(patch("/api/v1/users/me").with(userJwt(jane.getId(), "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"+447700900123\"}"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class Addresses {

        @Test
        void createsAnAddressAndReturnsItsLocation() throws Exception {
            Address address = Address.create(UUID.randomUUID(), jane.getId(),
                    new AddressDetails("Home", "221B Baker Street", null, "London", "NW1 6XE", "GB", 51.5, -0.15),
                    true, Instant.now());
            given(addresses.create(eq(jane.getId()), any(), anyBoolean())).willReturn(address);

            mvc.perform(post("/api/v1/users/me/addresses").with(userJwt(jane.getId(), "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON).content(ADDRESS_JSON))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/users/me/addresses/" + address.getId()))
                    .andExpect(jsonPath("$.isDefault").value(true));
        }

        @Test
        void validatesCountryAndCoordinates() throws Exception {
            mvc.perform(post("/api/v1/users/me/addresses").with(userJwt(jane.getId(), "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ADDRESS_JSON.replace("\"GB\"", "\"GBR\"").replace("51.5237", "91")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.length()").value(2));
        }

        @Test
        void foreignAddressIs404() throws Exception {
            UUID addressId = UUID.randomUUID();
            given(addresses.update(eq(jane.getId()), eq(addressId), any(), anyBoolean()))
                    .willThrow(new ResourceNotFoundException("Address"));

            mvc.perform(put("/api/v1/users/me/addresses/" + addressId).with(userJwt(jane.getId(), "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON).content(ADDRESS_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        void deletes() throws Exception {
            UUID addressId = UUID.randomUUID();

            mvc.perform(delete("/api/v1/users/me/addresses/" + addressId).with(userJwt(jane.getId(), "CUSTOMER")))
                    .andExpect(status().isNoContent());
            verify(addresses).delete(jane.getId(), addressId);
        }

        @Test
        void malformedIdIs400() throws Exception {
            mvc.perform(delete("/api/v1/users/me/addresses/not-a-uuid").with(userJwt(jane.getId(), "CUSTOMER")))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class Admin {

        private final UUID adminId = UUID.randomUUID();

        @Test
        void customersAreForbidden() throws Exception {
            mvc.perform(get("/api/v1/admin/users").with(userJwt(jane.getId(), "CUSTOMER")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            verifyNoInteractions(adminUsers);
        }

        @Test
        void adminsCanSearch() throws Exception {
            given(adminUsers.search(any(), any(), anyInt(), anyInt()))
                    .willReturn(new PageImpl<>(List.of(jane), PageRequest.of(0, 20), 1));

            mvc.perform(get("/api/v1/admin/users").param("status", "ACTIVE").with(userJwt(adminId, "ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].email").value("jane@example.com"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.page").value(0));
        }

        @Test
        void pageSizeIsBounded() throws Exception {
            mvc.perform(get("/api/v1/admin/users").param("size", "1000").with(userJwt(adminId, "ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        void changingOwnStatusIs422() throws Exception {
            given(adminUsers.changeStatus(adminId, adminId, UserStatus.SUSPENDED))
                    .willThrow(new SelfStatusChangeException());

            mvc.perform(patch("/api/v1/admin/users/" + adminId + "/status").with(userJwt(adminId, "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("CANNOT_CHANGE_OWN_STATUS"));
        }
    }
}
