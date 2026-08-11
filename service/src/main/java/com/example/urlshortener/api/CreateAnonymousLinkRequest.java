package com.example.urlshortener.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/public/links}.
 *
 * <p>A separate record from {@link CreateLinkRequest} rather than a reuse of it,
 * and the separation is the mechanism rather than tidiness (A11): with
 * {@code spring.jackson.deserialization.fail-on-unknown-properties} enabled, a
 * property this record does not declare is a 400. Reusing
 * {@code CreateLinkRequest} here would silently <em>accept</em> {@code alias}
 * and {@code expiresAt} on a path that must refuse both.
 *
 * <p>Why those two are refused rather than honoured:
 *
 * <ul>
 *   <li>{@code alias} - aliases share one namespace with generated codes and a
 *       code is never reissued, even after a soft delete. An unidentified
 *       caller choosing memorable aliases is permanent namespace squatting with
 *       no owner to revoke it and nobody to rate-limit by identity.</li>
 *   <li>{@code expiresAt} - the expiry of an anonymous link is
 *       {@code app.links.anonymous-ttl} from creation and nothing else (A9).
 *       Nobody owns the link, so nobody can shorten the expiry afterwards;
 *       letting the creator choose it once would be the only lever on a row no
 *       one can delete.</li>
 * </ul>
 *
 * @param longUrl absolute http(s) URL to shorten. Exactly the same two-stage
 *                check as the authenticated path: syntax and length here (400),
 *                then host policy and the threat denylist in the service (422),
 *                through the same {@link com.example.urlshortener.link.UrlValidator}
 *                and the same {@link com.example.urlshortener.threat.ThreatCheck}
 *                instance (A12/AC12), including the host normalisation that
 *                closes the trailing-dot and numeric-IPv4 evasions.
 */
public record CreateAnonymousLinkRequest(@NotBlank @Size(max = 2048) String longUrl) {}
