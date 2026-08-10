package com.example.urlshortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One abuse report, kept for the review process this build does not contain.
 *
 * <p>The reported code is stored as text rather than only as a foreign key, and
 * {@code linkId} is nullable, because a report is accepted for any syntactically
 * acceptable code whether or not it resolves. Refusing an unknown code here would
 * turn the one endpoint that takes a code from an untrusted caller into the
 * existence oracle every other endpoint is careful not to be -- so the row has to
 * be able to record a report against nothing.
 */
@Entity
@Table(name = "abuse_reports")
public class AbuseReportEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "domain", nullable = false, length = 255)
    private String domain;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "link_id")
    private UUID linkId;

    @Column(name = "reporter_customer_id", nullable = false)
    private UUID reporterCustomerId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AbuseReportEntity() {
        // for JPA
    }

    public AbuseReportEntity(
            UUID id,
            String domain,
            String code,
            UUID linkId,
            UUID reporterCustomerId,
            String reason,
            Instant createdAt) {
        this.id = id;
        this.domain = domain;
        this.code = code;
        this.linkId = linkId;
        this.reporterCustomerId = reporterCustomerId;
        this.reason = reason;
        this.createdAt = createdAt;
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

    public UUID getLinkId() {
        return linkId;
    }

    public UUID getReporterCustomerId() {
        return reporterCustomerId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
