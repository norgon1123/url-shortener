package com.example.urlshortener.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Body of {@code POST /api/v1/links}.
 *
 * @param longUrl   absolute http(s) URL to shorten. Length and syntax are
 *                  checked here (400); host policy and the threat denylist are
 *                  checked in the service (422) - see
 *                  {@link com.example.urlshortener.link.UrlValidator}.
 * @param alias     optional customer-chosen code (AC5). Shares one namespace
 *                  with generated codes so that a collision is a real collision
 *                  (AC6). An alias is, by construction, guessable; AC16's
 *                  unguessability guarantee covers generated codes only (A3).
 * @param expiresAt optional absolute expiry; must be in the future. Absent means
 *                  {@code app.links.default-ttl} from now, i.e. 30 days (A7).
 *                  There is no way to request a link that never expires - the
 *                  permanent tier is explicitly a later build.
 */
public record CreateLinkRequest(
        @NotBlank @Size(max = 2048) String longUrl,
        @Pattern(regexp = "^[A-Za-z0-9_-]{3,64}$") String alias,
        Instant expiresAt) {}
