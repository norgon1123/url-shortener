package com.example.urlshortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A host known for phishing or malware, which may never become a short link.
 *
 * <p>Hosts are stored lower-cased so that a lookup is an equality match on an
 * indexed column rather than a function over the table.
 *
 * <p>Rows arrive by migration. There is deliberately no admin endpoint -- nobody
 * asked for one, and an API that lets a caller add entries to a global denylist
 * is a larger surface than the feature justifies -- which does mean that marking
 * a URL bad in production is currently a migration under an approval gate. That
 * is a real operational gap, and it is recorded rather than papered over.
 */
@Entity
@Table(name = "threat_denylist")
public class ThreatDenylistEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "host", nullable = false, length = 255, unique = true)
    private String host;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ThreatDenylistEntity() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
