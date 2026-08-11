package com.example.urlshortener.api;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Body of {@code PATCH /api/v1/links/{code}}. Exactly one field, on purpose.
 *
 * <p>AC11 requires a link's expiry to be changeable without a code change, while
 * "editing a link" is out of scope. This is the narrowest endpoint that
 * satisfies the first without reopening the second: the target URL is immutable
 * for the life of the link, so a short link's destination cannot be swapped
 * after it has been shared - which is the bait-and-switch this API must not
 * offer (AC21).
 *
 * <p>Immutability is enforced mechanically rather than by omission: unknown
 * properties in any request body are rejected with 400, so
 * {@code {"longUrl": "..."}} is an error rather than a silently ignored field.
 *
 * @param expiresAt new absolute expiry; required, and must be in the future.
 *                  Setting an expiry in the past to take a link down is not
 *                  supported - {@code DELETE} does that, and it invalidates the
 *                  caches, which a past timestamp would not.
 */
public record UpdateLinkExpiryRequest(@NotNull Instant expiresAt) {}
