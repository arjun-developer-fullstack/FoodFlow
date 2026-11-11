package com.foodflow.userservice.user.domain;

/** Platform roles, carried in the JWT {@code roles} claim. */
public enum Role {
    CUSTOMER,
    RESTAURANT_OWNER,
    COURIER,
    ADMIN
}
