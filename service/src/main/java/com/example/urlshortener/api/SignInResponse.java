package com.example.urlshortener.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Body of a successful {@code POST /api/v1/sessions}.
 *
 * <p>The token is a JWT signed with an asymmetric key and verified locally by
 * whichever service receives it, with no call back to a login system on any
 * request (AC18). It is presented as {@code Authorization: Bearer <token>}.
 *
 * @param accessToken the signed session token
 * @param tokenType   always {@code Bearer}
 * @param expiresAt   absolute expiry, 24 hours out (Q8). Non-refreshable, and
 *                    there is no revocation list: sign-out and revocation are
 *                    out of scope, and a denylist would reintroduce exactly the
 *                    per-request lookup AC18 forbids. Twenty-four hours is
 *                    therefore the blast radius of a leaked token, and it is
 *                    configuration ({@code app.session.ttl}), not code.
 * @param customerId  the authenticated customer, so a client need not decode the
 *                    token to know who it is
 */
public record SignInResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        UUID customerId) {}
