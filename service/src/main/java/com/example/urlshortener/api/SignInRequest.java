package com.example.urlshortener.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/sessions}.
 *
 * <p>Accounts are seeded by migration for this build; there is no registration
 * endpoint, because provisioning was never asked for (A18).
 *
 * @param email    the customer's login identity. One customer is one identity
 *                 (A16); roles, teams and multiple users per customer are out of
 *                 scope and a later split stays additive.
 * @param password the raw password, checked against a memory-hard hash. Never
 *                 logged, never echoed in any response or error.
 */
public record SignInRequest(
        @NotBlank @Size(max = 320) String email,
        @NotBlank @Size(max = 256) String password) {}
