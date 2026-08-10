package com.example.urlshortener.auth;

import java.util.Optional;

/**
 * Verifies a session token locally: signature, issuer and expiry, no network
 * call and no database read (AC18).
 */
public interface JwtVerifier {

    /**
     * @param token the bearer token, without the {@code Bearer } prefix
     * @return the caller, or empty if the token is absent, malformed, expired,
     *         signed by another key or otherwise unverifiable
     *
     *         <p>Empty rather than an exception per failure kind, deliberately:
     *         every failure produces the same 401 with the same body, so the
     *         reason never reaches the caller and cannot be used to probe which
     *         tokens are close to valid.
     */
    Optional<CurrentCustomer> verify(String token);
}
