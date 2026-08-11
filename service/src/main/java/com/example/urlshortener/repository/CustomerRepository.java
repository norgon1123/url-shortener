package com.example.urlshortener.repository;

import com.example.urlshortener.domain.CustomerEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Customer accounts: two seeded by migration, the rest created through
 * {@code POST /api/v1/customers}.
 */
public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {

    /**
     * Email is an identity, not a string: addresses differing only in case are the
     * same account, and a case-sensitive lookup would let one be used to probe for
     * the other.
     *
     * <p>This returns an {@code Optional}, which is a promise the table has to
     * keep. It rests on the unique index over {@code lower(email)}: without it,
     * two case-variant rows can both exist and this query throws
     * {@code IncorrectResultSizeDataAccessException} - a 500 on the sign-in
     * endpoint, for an account that may have been seeded. That is why the
     * constraint migration has to land before sign-up starts inserting rows.
     */
    Optional<CustomerEntity> findByEmailIgnoreCase(String email);
}
