package com.gbhackathon.AICareerCode.service;

/** Thrown when a request carries a value the profile contract does not allow. Maps to HTTP 400. */
public class ProfileValidationException extends RuntimeException {
    public ProfileValidationException(String message) {
        super(message);
    }
}
