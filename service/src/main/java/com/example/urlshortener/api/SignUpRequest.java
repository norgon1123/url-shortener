package com.example.urlshortener.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/customers}.
 *
 * <p>The requirement says "account name"; this build reads that as the email
 * address (A5/Q1). The reasoning is in the design rationale and it is the one
 * decision on this endpoint most worth re-examining if the architect rules
 * otherwise: a separate username would add a {@code customers} column, a second
 * sign-in identifier, and an edit to {@link SignInRequest}, which is frozen.
 *
 * <p>The field names are deliberately identical to {@link SignInRequest}, so
 * that "create an account, then sign in with those credentials" (AC5) is the
 * same two fields posted to two paths rather than a translation the caller has
 * to perform.
 *
 * <p>Strict bodies apply: an unknown property is a 400, so a caller that sends
 * {@code username} or {@code passwordConfirm} finds out rather than having it
 * silently dropped.
 *
 * @param email    the account name and the login identity. Uniqueness is over
 *                 the lower-cased value and is enforced by a database
 *                 constraint, not by a read-then-write (A6): only a constraint
 *                 can decide the concurrent case AC6 names. Validated with
 *                 {@code @Email}, so a value with no {@code @} is 400
 *                 {@code invalid_request} with {@code fields.email}; the
 *                 320-character ceiling matches the column and
 *                 {@link SignInRequest}.
 * @param password the raw password. Minimum 12 characters and maximum 256
 *                 (Q8): the maximum matches {@link SignInRequest} so a password
 *                 that can be chosen can always be typed back, and the minimum
 *                 is the smallest defensible rule that stops a one-character
 *                 password. No composition rules and no denylist - the
 *                 requirement speaks only to how passwords are held, and
 *                 composition rules push users towards predictable
 *                 substitutions. A violation is 400 with {@code fields.password}
 *                 and the message names the rule, never the value. The raw
 *                 value is never logged and appears in no response body (A7).
 */
public record SignUpRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 12, max = 256) String password) {}
