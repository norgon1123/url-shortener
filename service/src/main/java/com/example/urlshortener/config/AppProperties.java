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
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Abuse abuse) {

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
     * @param anonymousTtl how long a link created through
     *                     {@code POST /api/v1/public/links} lives, applied at
     *                     creation as an absolute instant and never supplied by
     *                     the caller (A9). "One month" is read as 30 days,
     *                     matching the reading {@code defaultTtl} already
     *                     takes, so the two agree unless somebody deliberately
     *                     separates them. It is a <em>separate</em> property
     *                     precisely so that it can be tuned down for abuse
     *                     reasons without changing what paying customers get,
     *                     and so that the rollback plan for anonymous links is
     *                     "stop creating them and let 30 days drain" rather
     *                     than a data deletion.
     */
    public record Links(
            @DefaultValue("P30D") Duration defaultTtl,
            @DefaultValue("2048") int maxUrlLength,
            @DefaultValue("P30D") Duration anonymousTtl) {}

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
     * Token buckets (A13). One window, seven buckets, all of them numbers rather
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
     * <p>The two newest buckets are both keyed by {@code getRemoteAddr()},
     * because an unauthenticated caller has no customer id and the service does
     * not trust {@code X-Forwarded-For} (Q5 - trusting a client-supplied header
     * with no configured trusted-proxy list would make every IP-keyed bucket in
     * the service spoofable, including the click and not-found buckets that
     * already exist). The operational consequence is real and is documented in
     * the runbook rather than fixed here: behind a proxy that does not preserve
     * the source address, every anonymous caller shares one bucket and these
     * numbers become global ceilings rather than per-caller ones.
     *
     * @param window                  the refill period; capacity equals the
     *                                per-minute figure.
     * @param signUpPerMinute         {@code POST /api/v1/customers}, keyed by
     *                                client IP (A14). Sign-up is the second
     *                                unauthenticated endpoint that writes to
     *                                PostgreSQL, and it does a 25 ms, 16 MiB
     *                                Argon2id hash before it gets there, so it
     *                                is a CPU and memory vector as much as a
     *                                storage one. 60 matches the sign-in bucket
     *                                on the same reasoning and the same key.
     * @param anonymousCreatePerMinute {@code POST /api/v1/public/links}, keyed
     *                                by client IP. 30 is an order of magnitude
     *                                under {@code writePerMinute}, which is the
     *                                concrete meaning given to AC14's "the
     *                                anonymous path cannot be used to bypass
     *                                the limits on authenticated creation": the
     *                                unauthenticated route is never the cheaper
     *                                way to mint links. It is the tightest
     *                                bucket in the service after not-found,
     *                                because an anonymous link occupies the
     *                                shared code namespace permanently and no
     *                                owner can ever delete it.
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("3000") int clickPerMinute,
            @DefaultValue("300") int notFoundPerMinute,
            @DefaultValue("300") int writePerMinute,
            @DefaultValue("60") int abuseReportPerMinute,
            @DefaultValue("60") int signInPerMinute,
            @DefaultValue("60") int signUpPerMinute,
            @DefaultValue("30") int anonymousCreatePerMinute,
            @DefaultValue("PT1M") Duration window) {}

    /**
     * Who may get a link taken down by reporting it.
     *
     * <p>The abuse endpoint blocks a link the moment it is reported, with no
     * moderation queue and no unblock path, and its only bound was a bucket keyed
     * by reporter at 60 a minute. That bound assumed reporters were provisioned by
     * hand. Self-service sign-up removes the assumption: a reporter id now costs
     * one unauthenticated request, so a bucket keyed by reporter bounds nothing and
     * any published link could be taken down permanently by anyone.
     *
     * @param minReporterAge how long an account must have existed before it may
     *                       take down a link that already existed when the account
     *                       was created. An account may always act on links minted
     *                       after it signed up, so an ordinary customer reporting
     *                       the phishing link they were just sent is unaffected;
     *                       what the age buys is that taking down something already
     *                       published has to be planned this far in advance rather
     *                       than done with an account created for the purpose. Seven
     *                       days is chosen as the shortest period that is longer
     *                       than an opportunistic attack and shorter than a customer
     *                       would wait for anything; set it to {@code PT0S} to
     *                       restore the pre-sign-up behaviour, knowingly. It is not
     *                       a complete closure - an aged account farm defeats it -
     *                       and the real fix, a queue or a second reporter before a
     *                       block, is a subsystem this build does not have.
     */
    public record Abuse(@DefaultValue("P7D") Duration minReporterAge) {}
}
