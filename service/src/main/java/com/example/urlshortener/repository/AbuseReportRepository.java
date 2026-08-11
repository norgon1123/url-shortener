package com.example.urlshortener.repository;

import com.example.urlshortener.domain.AbuseReportEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The audit trail behind every takedown.
 *
 * <p>Write-only in this build: a report blocks its link immediately because there
 * is no moderation console for a queued report to wait for, and these rows are
 * what a later review process reads.
 */
public interface AbuseReportRepository extends JpaRepository<AbuseReportEntity, UUID> {
}
