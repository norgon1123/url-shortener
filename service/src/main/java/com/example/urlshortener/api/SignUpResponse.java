package com.example.urlshortener.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Body of a successful {@code POST /api/v1/customers}.
 *
 * <p>The account and nothing else. No session token (Q7): AC5 describes signing
 * in as a separate subsequent step, and issuing a token from an endpoint that is
 * not the session endpoint would duplicate the issuing path - two places to get
 * token lifetime, claims and the rate limit wrong instead of one.
 *
 * <p>No password field of any kind, in any form. AC7 is about what is stored;
 * echoing what was submitted would defeat it on the wire instead.
 *
 * <p>There is deliberately no {@code Location} header on the 201. See the
 * operation description in {@code artifacts/openapi.yaml}: there is no
 * {@code GET /api/v1/customers/{id}} in this build, so a {@code Location} would
 * point at a path that answers 401 without a token and 405 with one - for every
 * caller, including the account that was just created. That is the same
 * objection that removed the header from anonymous creation (Q9), and taking it
 * one way here and the other way there would be an inconsistency a client would
 * have to learn.
 *
 * @param customerId the new account's id, the same value {@link SignInResponse}
 *                   returns after signing in with these credentials
 * @param email      the stored address, echoed back exactly as stored so a
 *                   caller can see what its address was recorded as
 * @param createdAt  UTC instant, ISO-8601
 */
public record SignUpResponse(UUID customerId, String email, Instant createdAt) {}
