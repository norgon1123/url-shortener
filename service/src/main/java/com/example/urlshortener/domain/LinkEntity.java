package com.example.urlshortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One short link.
 *
 * <p>Uniqueness is {@code (domain, code)} rather than {@code code} alone, from
 * day one and with a single configured domain in this build, so that
 * customer-owned domains later arrive as data rather than as a migration on the
 * busiest table in the system.
 *
 * <p>The stored {@code status} is only ever {@link LinkStatus#ACTIVE},
 * {@link LinkStatus#DELETED} or {@link LinkStatus#BLOCKED}.
 * {@link LinkStatus#EXPIRED} is derived from {@link #getExpiresAt()} whenever the
 * row is read and is never written: storing it would make a link's status depend
 * on a sweeper having run, and a sweeper that fell behind would keep an expired
 * link redirecting.
 *
 * <p>Deletion is soft. The row and its click total are retained and the code is
 * never reissued -- reissuing would hand an old link's audience to a new owner's
 * target, which is a security problem rather than an untidiness.
 *
 * <p>{@code clickCount} is the <em>durable</em> total only. Clicks land in Redis
 * on the hot path and are drained into this column in batches, so the figure
 * reported to a customer is this number plus whatever has not been drained yet.
 *
 * <p>Both timestamps are held at the precision the column has. See
 * {@link #atStoredPrecision(Instant)}: a response is built from this object
 * whether it was just constructed or just read back, and those two have to be
 * the same answer to the same question.
 */
@Entity
@Table(
        name = "links",
        uniqueConstraints = @UniqueConstraint(name = "ux_links_domain_code", columnNames = {"domain", "code"}))
public class LinkEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "domain", nullable = false, length = 255)
    private String domain;

    @Column(name = "code", nullable = false, length = 64, updatable = false)
    private String code;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "long_url", nullable = false, length = 2048, updatable = false)
    private String longUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LinkStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "click_count", nullable = false)
    private long clickCount;

    protected LinkEntity() {
        // for JPA
    }

    public LinkEntity(
            UUID id,
            String domain,
            String code,
            UUID customerId,
            String longUrl,
            Instant createdAt,
            Instant expiresAt) {
        this.id = id;
        this.domain = domain;
        this.code = code;
        this.customerId = customerId;
        this.longUrl = longUrl;
        this.status = LinkStatus.ACTIVE;
        this.createdAt = atStoredPrecision(createdAt);
        this.expiresAt = atStoredPrecision(expiresAt);
        this.clickCount = 0L;
    }

    /**
     * Rounds an instant down to the precision {@code TIMESTAMP(6)} can hold.
     *
     * <p>PostgreSQL rounds anything finer to the nearest microsecond, so an
     * {@link Instant#now()} kept as-is in memory is not the value a later read of
     * the row returns. Creating a link and then fetching it would then report two
     * different creation times for one immutable field. Normalising on the way in
     * costs sub-microsecond accuracy nobody asked for and makes the two agree.
     */
    private static Instant atStoredPrecision(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The status to report to the owner: the stored one, unless the row is active
     * and its expiry has passed.
     */
    public LinkStatus statusAt(Instant now) {
        if (status == LinkStatus.ACTIVE && !expiresAt.isAfter(now)) {
            return LinkStatus.EXPIRED;
        }
        return status;
    }

    /** Whether a click on this code should redirect at {@code now}. */
    public boolean isResolvableAt(Instant now) {
        return statusAt(now) == LinkStatus.ACTIVE;
    }

    /** Soft delete. Idempotent, so a repeated delete is not an error. */
    public void markDeleted() {
        this.status = LinkStatus.DELETED;
    }

    /**
     * Takedown after an abuse report. A link already deleted stays deleted -- both
     * answer the same 404 on the click path, and overwriting would lose the reason
     * the row is in the state it is in.
     */
    public void markBlocked() {
        if (status == LinkStatus.ACTIVE) {
            this.status = LinkStatus.BLOCKED;
        }
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = atStoredPrecision(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public String getDomain() {
        return domain;
    }

    public String getCode() {
        return code;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public LinkStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public long getClickCount() {
        return clickCount;
    }
}
