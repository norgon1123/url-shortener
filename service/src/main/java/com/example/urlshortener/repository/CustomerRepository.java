package com.example.urlshortener.repository;

import com.example.urlshortener.domain.CustomerEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Customer accounts, seeded by migration; there is no registration endpoint. */
public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {

    /**
     * Email is an identity, not a string: addresses differing only in case are the
     * same account, and a case-sensitive lookup would let one be used to probe for
     * the other.
     */
    Optional<CustomerEntity> findByEmailIgnoreCase(String email);
}
