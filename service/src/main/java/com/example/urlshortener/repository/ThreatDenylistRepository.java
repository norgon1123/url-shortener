package com.example.urlshortener.repository;

import com.example.urlshortener.domain.ThreatDenylistEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Hosts that may never become a short link (AC21). */
public interface ThreatDenylistRepository extends JpaRepository<ThreatDenylistEntity, UUID> {

    /** Hosts are stored lower-cased, so callers must normalise before asking. */
    boolean existsByHost(String host);
}
