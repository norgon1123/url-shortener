package com.example.urlshortener.domain;

/**
 * The status of a link as reported to its owner.
 *
 * <p>Three of these are stored on the row; {@link #EXPIRED} is derived at read
 * time from {@code expires_at}, never persisted. Storing it would mean a link's
 * status depended on a sweeper having run, which is a second source of truth for
 * something a timestamp comparison already answers - and a sweeper that falls
 * behind would keep redirecting an expired link (AC10).
 *
 * <p>None of these values ever reaches an unauthenticated clicker: on the click
 * path everything except {@link #ACTIVE} is a 404 with the same body as a code
 * that was never issued.
 */
public enum LinkStatus {

    /** Live and within its expiry: the only status that redirects. */
    ACTIVE,

    /** Derived, not stored: {@code expires_at} is in the past. */
    EXPIRED,

    /**
     * Soft-deleted by its owner. The row and its click total are retained and
     * the code is never reissued - reissuing would silently point an old link's
     * audience at a new owner's target (A11).
     */
    DELETED,

    /** Taken down after an abuse report or a threat-check hit (AC9/AC21). */
    BLOCKED
}
