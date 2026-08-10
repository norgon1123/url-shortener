package com.example.urlshortener.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The service's configuration surface, frozen as types.
 *
 * <p>This record is part of the frozen contract for one reason: it is the only
 * thing the two parallel branches share about configuration. {@code implement}
 * writes {@code application.yml} and reads these values; {@code author-tests}
 * never sees {@code application.yml} but must be able to drive a bucket down to
 * a testable size, and it can only do that if it knows the property names. Every
 * key below therefore binds from {@code app.*} with the relaxed name a test
 * would guess ({@code refillPerMinute} &rarr; {@code app.rate-limit.click-per-minute}
 * style kebab-case), and every one of them has a default here, so a key missing
 * from {@code application.yml} degrades to this file rather than failing to bind.
 *
 * <p>Tests override a value the ordinary Spring way, e.g.
 * {@code @SpringBootTest(properties = "app.rate-limit.not-found-per-minute=5")}.
 *
 * <p>Registration is {@code implement}'s job: either
 * {@code @EnableConfigurationProperties(AppProperties.class)} on the application
 * class or {@code @ConfigurationPropertiesScan}. Nothing here does it, because
 * the application class belongs to the scaffold.
 *
 * @param baseUrl public origin used to build the {@code shortUrl} returned by the
 *                API. Kept separate from {@code domain} so the future
 *                customer-owned-domain feature changes the host without changing
 *                the uniqueness key.
 * @param domain  the link namespace this instance serves. Code uniqueness is
 *                {@code (domain, code)} from day one; there is exactly one
 *                domain in this build.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("http://localhost:8080") String baseUrl,
        @DefaultValue("localhost") String domain,
        @DefaultValue Links links,
        @DefaultValue Cache cache,
        @DefaultValue Session session,
        @DefaultValue Click click,
        @DefaultValue Threat threat,
        @DefaultValue RateLimit rateLimit) {

    /**
     * Link creation policy.
     *
     * @param defaultTtl   how long a link lives when the caller does not say
     *                     (AC10 "about a month" = exactly 30 days, A7). Stored
     *                     per row as an absolute instant, so changing this value
     *                     never rewrites existing links.
     * @param maxUrlLength ceiling on a submitted long URL (A14); 2048 is the
     *                     pragmatic browser-compatible limit and bounds the
     *                     storage burn from junk-link attacks.
     */
    public record Links(
            @DefaultValue("P30D") Duration defaultTtl,
            @DefaultValue("2048") int maxUrlLength) {}

    /**
     * Redirect resolution cache (A10).
     *
     * <p>{@code ttl} is a floor under the published takedown bound, not the
     * mechanism that delivers it: deletes, expiry changes and abuse takedowns
     * actively invalidate. It must stay at or below 60 seconds, because a missed
     * invalidation is bounded by exactly this number and AC9 is the figure quoted
     * to fraud teams. The scaffold shipped 5 minutes; that would breach it by
     * four minutes with nothing failing.
     *
     * @param negativeTtl lifetime of a cached "no such code" entry. Negative
     *                    caching is what keeps an enumeration sweep off
     *                    PostgreSQL (AC19); it is bounded by the same reasoning.
     */
    public record Cache(
            @DefaultValue("PT60S") Duration ttl,
            @DefaultValue("PT60S") Duration negativeTtl,
            @DefaultValue("link:") String keyPrefix) {}

    /**
     * Stateless session tokens (A5/AC18).
     *
     * <p>{@code privateKeyPem} and {@code publicKeyPem} are supplied by the
     * environment in the {@code ${VAR:}} style {@code application.yml} already
     * uses for database credentials; no key material is ever literal in the
     * repository. U5 was never settled, so the reading taken here is: when both
     * are blank the service generates an ephemeral keypair at startup and logs a
     * warning. That is correct for a single instance and for tests, and wrong
     * for more than one replica - a token issued by one instance will not verify
     * on another. A deployment with replicas must supply the keys.
     *
     * @param ttl token lifetime; 24 hours, non-refreshable, no revocation list
     *            (Q8). Sign-out and revocation are out of scope.
     */
    public record Session(
            @DefaultValue("url-shortener") String issuer,
            @DefaultValue("PT24H") Duration ttl,
            @DefaultValue("") String privateKeyPem,
            @DefaultValue("") String publicKeyPem) {}

    /**
     * Click counting (A9).
     *
     * @param flushInterval how often the Redis deltas are drained into
     *                      PostgreSQL. It does not affect the number the API
     *                      reports: a read returns the durable total plus the
     *                      un-flushed delta, so AC7 holds between flushes.
     */
    public record Click(
            @DefaultValue("PT5S") Duration flushInterval,
            @DefaultValue("500") int flushBatchSize,
            @DefaultValue("clicks:") String keyPrefix) {}

    /**
     * Threat checking on the create path (Q2/AC21).
     *
     * @param failOpen when the checker cannot answer, accept the link and log at
     *                 WARN. This follows AC20's stated preference for
     *                 availability. It is a deliberate, reviewable choice: the
     *                 alternative refuses every creation whenever the denylist
     *                 store is unreachable.
     */
    public record Threat(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("true") boolean failOpen) {}

    /**
     * Token buckets (A13). One window, five buckets, all of them numbers rather
     * than code.
     *
     * <p>The buckets are separate because AC19 names three different attacks with
     * three different natural keys, and because throttling a hot link's genuine
     * traffic (AC22) is exactly what a single shared bucket would do. In
     * particular {@code notFoundPerMinute} is far tighter than
     * {@code clickPerMinute}: an enumeration sweep is a sequence of 404s, while a
     * viral link is a sequence of 302s.
     *
     * <p>The defaults are production defaults, set high enough that an ordinary
     * integration test never trips them from a single source address. A test that
     * wants to observe a 429 lowers the relevant number.
     *
     * @param window the refill period; capacity equals the per-minute figure.
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("3000") int clickPerMinute,
            @DefaultValue("300") int notFoundPerMinute,
            @DefaultValue("300") int writePerMinute,
            @DefaultValue("60") int abuseReportPerMinute,
            @DefaultValue("60") int signInPerMinute,
            @DefaultValue("PT1M") Duration window) {}
}
