package com.example.urlshortener.threat;

import java.net.URI;

/**
 * Decides whether a URL may be shortened (AC21).
 *
 * <p>A port with two implementations in this build - a PostgreSQL-backed
 * domain/URL denylist and a no-op - because Q2 was never answered by a human.
 * No third-party reputation feed is integrated: none is named in the
 * constraints, and putting a paid external call on the create path is a
 * procurement and latency decision rather than an engineering one. If a real
 * feed is expected, this interface is where it lands and the rest of the design
 * does not move.
 *
 * <p>The second half of AC21 - "an existing link found to point at one stops
 * redirecting" - is served by the takedown path, not by a background rescan: an
 * abuse report or a denylist hit flips the link to BLOCKED and invalidates the
 * caches, which is the same 60-second bound as a delete.
 *
 * <p>Implementations must not throw. A checker that is down returns
 * {@link ThreatVerdict#UNAVAILABLE}; an exception escaping into the create path
 * would turn a degraded dependency into a 500.
 */
public interface ThreatCheck {

    /**
     * @param url a syntactically valid absolute http(s) URL
     * @return the verdict; never null
     */
    ThreatVerdict check(URI url);
}
