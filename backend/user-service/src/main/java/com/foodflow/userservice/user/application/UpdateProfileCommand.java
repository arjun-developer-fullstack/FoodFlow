package com.foodflow.userservice.user.application;

/**
 * Partial profile update. {@code null} fields are left unchanged; an empty {@code phone}
 * removes the phone number.
 */
public record UpdateProfileCommand(String fullName, String phone) {
}
