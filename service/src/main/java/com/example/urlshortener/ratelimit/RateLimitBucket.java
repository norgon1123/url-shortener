package com.example.urlshortener.ratelimit;

/**
 * The token buckets (A13/AC19).
 *
 * <p>AC19 names three attacks - link mining, existence scraping, bulk junk-link
 * creation - and they have different natural keys and wildly different
 * legitimate volumes. One shared bucket would either be loose enough to let a
 * scraper work or tight enough to throttle a link that has gone viral, and AC22
 * says a viral link must keep being served.
 */
public enum RateLimitBucket {

    /**
     * Successful clicks, keyed by client IP. Sized for a hot link, because the
     * legitimate case here is a genuine spike (AC22).
     */
    CLICK,

    /**
     * Requests for codes that do not resolve, keyed by client IP, and far
     * tighter than {@link #CLICK}. This is the bucket that actually stops
     * enumeration: a sweep is a long run of 404s, whereas a real audience
     * produces 302s. Separating them is what lets us throttle the scraper
     * without touching the viral link.
     */
    NOT_FOUND,

    /** Authenticated writes, keyed by customer id: the junk-link storage burn. */
    WRITE,

    /**
     * Abuse reports, keyed by customer id. A report takes a link down
     * immediately with no human in the loop, so the limit is what stops the
     * feature being a cheap way to kill a competitor's links at scale.
     */
    ABUSE_REPORT,

    /**
     * Sign-in attempts, keyed by client IP. Not named in A13; added because an
     * unthrottled sign-in is an open credential-stuffing target, and AC17's
     * promise about stolen databases is worth little if the passwords can be
     * guessed at the front door.
     */
    SIGN_IN
}
