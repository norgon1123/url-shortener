package com.example.urlshortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer account.
 *
 * <p>One customer is one login identity: there is no registration endpoint and
 * no role model in this build, so rows arrive by migration and the only thing
 * this table is asked to do is answer "who is this, and does this password match
 * what we stored".
 *
 * <p>{@code passwordHash} is the full encoded Argon2id value, algorithm
 * parameters and salt included. The plaintext is never stored, never logged and
 * never returned.
 */
@Entity
@Table(name = "customers")
public class CustomerEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 320, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CustomerEntity() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
