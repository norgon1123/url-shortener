package com.example.urlshortener.auth;

import com.example.urlshortener.api.SignInRequest;
import com.example.urlshortener.api.SignInResponse;
import com.example.urlshortener.domain.CustomerEntity;
import com.example.urlshortener.error.ApiException;
import com.example.urlshortener.repository.CustomerRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exchanges credentials for a session token.
 *
 * <p>An unknown email and a wrong password produce the same refusal and take
 * about the same time to do it. The timing half matters as much as the body: a
 * sign-in that returns in a millisecond when the account does not exist, and in
 * the tens of milliseconds a memory-hard hash costs when it does, is an account
 * enumeration oracle that no amount of identical JSON hides. That is why the
 * unknown-account path still verifies against a real stored hash rather than
 * returning early.
 */
@Service
public class SignInService {

    private static final Logger log = LoggerFactory.getLogger(SignInService.class);

    /**
     * A real encoded hash of a value nobody knows, verified against when the email
     * is unknown so that both paths do the same work. Generated once at startup:
     * baking a constant into the source would put a credential-shaped string in the
     * repository for no benefit.
     */
    private final String absentAccountHash;

    private final CustomerRepository customers;
    private final PasswordHasher passwordHasher;
    private final JwtIssuer jwtIssuer;

    public SignInService(CustomerRepository customers, PasswordHasher passwordHasher, JwtIssuer jwtIssuer) {
        this.customers = customers;
        this.passwordHasher = passwordHasher;
        this.jwtIssuer = jwtIssuer;
        this.absentAccountHash = passwordHasher.hash(java.util.UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public SignInResponse signIn(SignInRequest request) {
        Optional<CustomerEntity> customer = customers.findByEmailIgnoreCase(request.email());
        String storedHash = customer.map(CustomerEntity::getPasswordHash).orElse(absentAccountHash);

        if (!passwordHasher.matches(request.password(), storedHash) || customer.isEmpty()) {
            log.info("Rejected a sign-in attempt");
            throw ApiException.invalidCredentials();
        }

        CustomerEntity account = customer.get();
        JwtIssuer.IssuedSession session = jwtIssuer.issue(account.getId(), account.getEmail());
        log.info("Issued a session for customer {}", account.getId());
        return new SignInResponse(session.token(), "Bearer", session.expiresAt(), account.getId());
    }
}
