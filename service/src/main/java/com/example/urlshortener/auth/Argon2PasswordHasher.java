package com.example.urlshortener.auth;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id password storage at Spring Security's current recommended parameters
 * (m=16384 KiB, t=2, p=1, 16-byte salt, 32-byte hash).
 *
 * <p>The parameters and a per-password salt are carried inside the encoded value,
 * so the stored form is self-describing and the cost can be raised later without
 * invalidating what is already stored. There is no application-side pepper: a
 * pepper adds a key somebody has to manage and turns its loss into total
 * credential loss, which is a worse failure than the one it defends against.
 *
 * <p>The encoder is stateless and thread-safe, so one instance serves every
 * request.
 */
@Component
public class Argon2PasswordHasher implements PasswordHasher {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) {
            return false;
        }
        return encoder.matches(rawPassword, storedHash);
    }
}
