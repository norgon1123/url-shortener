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
    SIGN_IN,

    /**
     * Account creation, keyed by client IP (A14).
     *
     * <p>The requirement asks for rate limiting on anonymous link creation and
     * says nothing about sign-up, but sign-up is the other unauthenticated
     * endpoint that writes to PostgreSQL, and it is the more expensive one: a
     * memory-hard Argon2id hash at m=16384,t=2 runs before the insert, so an
     * unmetered sign-up is a CPU and memory exhaustion vector as well as a
     * storage burn. It is also the only thing blunting the account-existence
     * disclosure that {@code ACCOUNT_UNAVAILABLE} necessarily carries, which
     * makes this bucket load-bearing rather than defensive tidiness.
     */
    SIGN_UP,

    /**
     * Anonymous link creation, keyed by client IP.
     *
     * <p>Client IP because there is no customer id to key on, and a separate
     * bucket rather than a share of {@link #WRITE} because the keys are
     * different in kind: WRITE meters one identified customer, this meters one
     * address. Keeping them apart is also what makes AC14's "cannot be used to
     * bypass" mechanical rather than argued - Redis keys are namespaced
     * {@code ratelimit:<bucket>:<key>}, so neither path can spend the other's
     * tokens even if both are exercised by the same caller.
     *
     * <p>Note what this bucket cannot do: the limiter fails open when Redis is
     * unreachable, so during a Redis outage this endpoint is unmetered. That is
     * the existing, deliberate limiter behaviour (a limiter that 429s the click
     * path when its store is down is a self-inflicted outage) and this change
     * does not alter it - but it does add an unauthenticated database write
     * behind it, which is worth an operator knowing.
     */
    ANONYMOUS_CREATE
}
