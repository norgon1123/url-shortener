package com.example.urlshortener.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * Issues session tokens at sign-in (A5).
 *
 * <p>Asymmetric signature, so any other service can verify with the public key
 * and none of them needs the signing key or a call to a login system (AC18).
 */
public interface JwtIssuer {

    /**
     * @param customerId subject of the token
     * @param email      carried as a claim so a verifier can populate
     *                   {@link CurrentCustomer} without a database read
     * @return the signed token and its absolute expiry
     */
    IssuedSession issue(UUID customerId, String email);

    /**
     * @param token     compact serialized JWS
     * @param expiresAt absolute expiry, {@code app.session.ttl} from issue
     */
    record IssuedSession(String token, Instant expiresAt) {}
}
