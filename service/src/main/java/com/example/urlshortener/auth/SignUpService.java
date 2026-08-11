package com.example.urlshortener.auth;

import com.example.urlshortener.api.SignUpRequest;
import com.example.urlshortener.api.SignUpResponse;
import com.example.urlshortener.domain.CustomerEntity;
import com.example.urlshortener.error.ApiException;
import com.example.urlshortener.repository.CustomerRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a customer account (AC4).
 *
 * <p>The password is held by the same {@link PasswordHasher} bean, at the same
 * parameters, that the seeded accounts' hashes were produced with, so there is one
 * mechanism holding every password in the table rather than one for accounts that
 * arrived by migration and another for accounts that arrived through the API
 * (AC7). The plaintext is never stored, never logged and never returned.
 *
 * <p><b>Uniqueness is decided by the insert, not by a preceding lookup.</b> A
 * read-then-write cannot promise that exactly one of two simultaneous sign-ups for
 * one address wins - both can see a free name at the same instant - so the row is
 * offered to the database and the unique index over {@code lower(email)} answers.
 * The loser becomes 409 {@code account_unavailable}, whether it lost by a
 * millisecond or by two years; the refusal says nothing that would tell the two
 * apart.
 */
@Service
public class SignUpService {

    private static final Logger log = LoggerFactory.getLogger(SignUpService.class);

    private final CustomerRepository customers;
    private final PasswordHasher passwordHasher;

    public SignUpService(CustomerRepository customers, PasswordHasher passwordHasher) {
        this.customers = customers;
        this.passwordHasher = passwordHasher;
    }

    /**
     * @return the created account
     * @throws ApiException {@code account_unavailable} (409) if the address is
     *         already registered, in any case variant
     */
    @Transactional
    public SignUpResponse signUp(SignUpRequest request) {
        // Truncated to what TIMESTAMP(6) holds, so the instant reported here is
        // the instant a later read of the row returns rather than a rounding away
        // from it.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CustomerEntity account = new CustomerEntity(
                UUID.randomUUID(), request.email(), passwordHasher.hash(request.password()), now);

        try {
            customers.saveAndFlush(account);
        } catch (DataIntegrityViolationException taken) {
            // Flushed inside the transaction precisely so the constraint answers
            // here, where it can be translated, rather than at commit time where
            // it would surface as a 500.
            log.info("Refused a sign-up for an account name that is already taken");
            throw ApiException.accountUnavailable();
        }

        log.info("Created customer {}", account.getId());
        return new SignUpResponse(account.getId(), account.getEmail(), account.getCreatedAt());
    }
}
