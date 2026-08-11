package com.example.urlshortener.auth;

/**
 * Password storage (AC17).
 *
 * <p>The stored form must not yield the original password to someone holding a
 * full copy of the database, which rules out anything that is merely a digest.
 * The implementation is Argon2id at OWASP-recommended parameters, with a
 * per-password salt inside the encoded value and no application-side pepper -
 * a pepper adds a key nobody asked us to manage and turns its loss into total
 * credential loss (A6).
 *
 * <p>{@code bcprov-jdk18on} is pinned in {@code pom.xml} for this: Spring
 * Security's Argon2 encoder calls into BouncyCastle but does not declare it, so
 * the absence surfaces as a {@code NoClassDefFoundError} at the first sign-in
 * rather than at build time.
 */
public interface PasswordHasher {

    /**
     * @param rawPassword plaintext, never logged
     * @return the full encoded hash, including algorithm parameters and salt, as
     *         stored in {@code customers.password_hash}
     */
    String hash(String rawPassword);

    /**
     * Verifies a candidate against a stored hash.
     *
     * <p>Must not short-circuit on a missing account: a caller that skips the
     * hash comparison when the email is unknown answers measurably faster and
     * turns sign-in into an account-enumeration oracle.
     */
    boolean matches(String rawPassword, String storedHash);
}
