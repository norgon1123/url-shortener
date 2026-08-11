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
 * <p>One customer is one login identity, with no role model in this build. Rows
 * now arrive two ways: the two seeded accounts still come from a migration, and
 * anybody may create one through {@code POST /api/v1/customers}. The table is
 * still only asked to answer "who is this, and does this password match what we
 * stored".
 *
 * <p>{@code passwordHash} is the full encoded Argon2id value, algorithm
 * parameters and salt included. The plaintext is never stored, never logged and
 * never returned. Sign-up produces it through the same
 * {@link com.example.urlshortener.auth.PasswordHasher} bean at the same
 * parameters the seeded rows were hashed with (AC7/A7), so one mechanism holds
 * every password in the table and the seeded hashes stay verifiable.
 *
 * <p><b>Uniqueness is over {@code lower(email)}, not over {@code email}.</b> The
 * {@code unique = true} mapping below reflects the case-sensitive
 * {@code ux_customers_email} that has always been there; the constraint this
 * class actually depends on is a functional unique index added by migration, and
 * it is what decides a duplicate sign-up (A6). The distinction is not academic:
 * {@code CustomerRepository.findByEmailIgnoreCase} treats addresses differing
 * only in case as one account, so without the functional index two such rows can
 * both insert and the <em>next</em> sign-in for either one fails on a non-unique
 * result - a 500 on an endpoint this change never touches, for an account that
 * may have been seeded.
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

    /**
     * A new account.
     *
     * <p>Every field is required and none is derived here: the id and the
     * creation instant are passed in rather than generated in the constructor so
     * that a caller can log what it is about to insert, and so the row is the
     * same whether it was just built or just read back.
     *
     * @param id           assigned by the caller, not by the database
     * @param email        stored as supplied; uniqueness is decided over the
     *                     lower-cased form by the database, not here
     * @param passwordHash the full encoded hash, never the plaintext. There is
     *                     deliberately no constructor that takes a raw password:
     *                     a type that can hold one is a type that can log one.
     * @param createdAt    UTC instant
     */
    public CustomerEntity(UUID id, String email, String passwordHash, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
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
