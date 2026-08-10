package com.example.urlshortener.repository;

import com.example.urlshortener.domain.LinkEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Links, always addressed by {@code (domain, code)}.
 *
 * <p>Every owner-scoped finder takes the customer id as part of the query rather
 * than fetching by code and comparing afterwards. Tenant isolation is an
 * invariant, not a check: a post-fetch comparison is one forgotten branch away
 * from a cross-tenant read, and "not yours" and "never existed" have to be the
 * same answer anyway.
 */
public interface LinkRepository extends JpaRepository<LinkEntity, UUID> {

    /** Resolution for the click path, which is not scoped to any customer. */
    Optional<LinkEntity> findByDomainAndCode(String domain, String code);

    /** The only way a management endpoint reaches a link. */
    Optional<LinkEntity> findByDomainAndCodeAndCustomerId(String domain, String code, UUID customerId);

    boolean existsByDomainAndCode(String domain, String code);

    /**
     * One page of a customer's links, newest first with the code as a tiebreak so
     * that two links created in the same millisecond cannot swap places between
     * requests. The sort is applied here rather than left to the caller because an
     * unstable order silently breaks paging.
     */
    @Query("select l from LinkEntity l where l.customerId = :customerId order by l.createdAt desc, l.code asc")
    Page<LinkEntity> findOwnedBy(@Param("customerId") UUID customerId, Pageable pageable);

    /**
     * Adds a drained batch of clicks to the durable total.
     *
     * <p>An in-database increment rather than read-modify-write: the flush job and
     * anything else touching the row must not be able to lose counts to a lost
     * update, and AC3 asks for an exact figure rather than nearly one.
     */
    @Modifying
    @Query("update LinkEntity l set l.clickCount = l.clickCount + :delta where l.id = :id")
    int addClicks(@Param("id") UUID id, @Param("delta") long delta);
}
