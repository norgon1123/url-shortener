package com.example.urlshortener.api;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/links/{code}/abuse-reports}.
 *
 * @param reason optional free text, retained with the reporter id and timestamp
 *               for the review process that this build does not contain (Q4).
 */
public record AbuseReportRequest(@Size(max = 500) String reason) {}
