package com.foodflow.userservice.user.domain;

import com.foodflow.platform.error.DomainException;
import com.foodflow.platform.error.ErrorCategory;

/** Business failures of the user/profile domain. Messages are safe to return to clients. */
public final class UserExceptions {

    private UserExceptions() {
    }

    public static final class EmailAlreadyRegisteredException extends DomainException {
        public EmailAlreadyRegisteredException() {
            super(ErrorCategory.CONFLICT, "EMAIL_ALREADY_REGISTERED", "An account with this e-mail already exists.");
        }
    }

    public static final class PhoneAlreadyInUseException extends DomainException {
        public PhoneAlreadyInUseException() {
            super(ErrorCategory.CONFLICT, "PHONE_ALREADY_IN_USE", "This phone number is used by another account.");
        }
    }

    public static final class AddressLimitExceededException extends DomainException {
        public AddressLimitExceededException(int limit) {
            super(ErrorCategory.BUSINESS_RULE, "ADDRESS_LIMIT_EXCEEDED",
                    "A user can store at most " + limit + " addresses.");
        }
    }

    public static final class InvalidAddressException extends DomainException {
        public InvalidAddressException(String reason) {
            super(ErrorCategory.VALIDATION, "INVALID_ADDRESS", "Invalid address: " + reason + ".");
        }
    }

    public static final class SelfStatusChangeException extends DomainException {
        public SelfStatusChangeException() {
            super(ErrorCategory.BUSINESS_RULE, "CANNOT_CHANGE_OWN_STATUS",
                    "Administrators cannot change the status of their own account.");
        }
    }
}
