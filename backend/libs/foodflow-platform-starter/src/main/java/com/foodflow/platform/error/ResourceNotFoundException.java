package com.foodflow.platform.error;

/**
 * Generic "not found / not visible" failure. Also used for resources owned by another user,
 * so that their existence is not disclosed.
 */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String resourceName) {
        super(ErrorCategory.NOT_FOUND, "RESOURCE_NOT_FOUND", resourceName + " not found");
    }
}
