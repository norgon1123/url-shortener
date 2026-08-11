package com.example.urlshortener.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/sessions}.
 *
 * <p>Unchanged by the addition of sign-up, and deliberately so: {@link SignUpRequest}
 * carries the same two fields, so the account created there signs in here with
 * no translation, and this frozen schema does not move (A5). Accounts now reach
 * the table two ways - the two seeded rows by migration, everyone else through
 * {@code POST /api/v1/customers} - and this endpoint cannot tell the difference.
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
