package com.example.urlshortener.support;

import com.example.urlshortener.config.CacheConfig;
import java.time.Duration;
import java.util.UUID;

/**
 * The constants both branches agreed on, in one place.
 *
 * <p>Everything here is either quoted verbatim from the frozen contract
 * ({@code artifacts/openapi.yaml} and the skeletons under
 * {@code src/main/java}) or is a value this harness invents once so that two
 * tests cannot invent it differently. The seeded accounts and the denylisted
 * hosts are the important half: there is no registration endpoint and no admin
 * endpoint, so accounts and denylist rows arrive by migration, and if the
 * migration and the tests each chose their own the failure would surface at a
 * join and look like a bug in the service.
 */
public final class Fixtures {

    private Fixtures() {
    }

    /**
     * A customer seeded by migration.
     *
     * @param id        the fixed UUID the migration writes
     * @param email     login identity
     * @param plaintext the credential the migration hashes with Argon2id; it is
     *                  never stored in this form and these accounts exist for
     *                  local and test use only
     */
    public record SeededCustomer(UUID id, String email, String plaintext) {
    }

    /** The first seeded customer. Owner of most links in this suite. */
    public static final SeededCustomer ALICE = new SeededCustomer(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "alice@example.com",
            "alice-dev-password");

    /**
     * The second seeded customer. Two customers exist because tenant isolation
     * (AC13) cannot be demonstrated with one.
     */
    public static final SeededCustomer BOB = new SeededCustomer(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "bob@example.com",
            "bob-dev-password");

    // ---- targets ---------------------------------------------------------

    /** An ordinary, acceptable target URL. */
    public static final String TARGET_URL = "https://example.com/a/very/long/path?with=query";

    /** A second acceptable target, for tests that need two distinguishable links. */
    public static final String OTHER_TARGET_URL = "https://example.org/another/target";

    /** Seeded in the threat denylist by migration: creating this must fail (AC21). */
    public static final String DENYLISTED_URL = "https://malware.example.com/campaign";

    /** The second seeded denylist entry (AC21). */
    public static final String PHISHING_URL = "https://phishing.example.net/signin";

    /** Loopback host: refused by host policy with the same message as a denylisted URL. */
    public static final String LOOPBACK_URL = "http://127.0.0.1:9/internal";

    /** Private address space: refused by host policy. */
    public static final String PRIVATE_HOST_URL = "http://10.0.0.1/internal";

    /** This service's own origin: refused, so a short link cannot point at a short link. */
    public static final String SELF_REFERENTIAL_URL = "http://localhost:8080/somewhere";

    /** Parses, but is not an absolute http(s) URL: 400 rather than 422. */
    public static final String NON_HTTP_URL = "ftp://example.com/file.txt";

    /** Not a URL at all: 400. */
    public static final String MALFORMED_URL = "not-a-url";

    // ---- codes -----------------------------------------------------------

    /**
     * A well-formed code of the contracted length that was never issued. Drawn
     * from the base62 alphabet so that it is indistinguishable, by shape alone,
     * from a code this service would generate.
     */
    public static final String UNISSUED_CODE = "ZzZzZzZzZzZzZzZzZzZzZz";

    /** A second unissued code, for tests that need two. */
    public static final String OTHER_UNISSUED_CODE = "QwErTyUiOpAsDfGhJkLzXc";

    /** Outside the alias charset and the generated alphabet: still answers 404, never 400. */
    public static final String MALFORMED_CODE = "not.a.code!";

    /** A well-formed, available alias. Tests that need uniqueness append to it. */
    public static final String ALIAS = "spring-sale";

    /** Reserved: refused with 400, because nobody holds it and 409 would imply somebody does. */
    public static final String RESERVED_ALIAS = "actuator";

    /** Reserved words are matched case-insensitively, so this is refused too. */
    public static final String RESERVED_ALIAS_MIXED_CASE = "ActuatoR";

    /** Two characters: shorter than the alias minimum, so 400. */
    public static final String TOO_SHORT_ALIAS = "ab";

    /** Outside {@code ^[A-Za-z0-9_-]{3,64}$}, so 400. */
    public static final String ILLEGAL_CHARSET_ALIAS = "spring sale!";

    /** Makes an alias unique per test run without a test having to think about it. */
    public static String uniqueAlias(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // ---- times -----------------------------------------------------------

    /**
     * The bound the business is held to for a delete or an abuse report, measured
     * from the response (AC9). Published in {@code openapi.yaml}; quoted here so
     * that a test asserts against the published number rather than a guess.
     */
    public static final Duration TAKEDOWN_BOUND = Duration.ofSeconds(60);

    /** "About a month" (AC10): {@code app.links.default-ttl}, 30 days. */
    public static final Duration DEFAULT_LINK_TTL = Duration.ofDays(30);

    /**
     * How far ahead a test sets an expiry it intends to sit through. Short enough
     * that the wait is a rounding error in the suite, long enough that creation
     * and the first click comfortably precede it.
     */
    public static final Duration SHORT_EXPIRY = Duration.ofSeconds(3);

    // ---- configuration keys ----------------------------------------------
    //
    // Every one of these binds to AppProperties, which is part of the frozen
    // contract precisely so that this harness can name them without ever seeing
    // application.yml. A test that needs a bucket small enough to exhaust, or an
    // expiry short enough to sit through, overrides the key here rather than
    // inventing a back door into the service.

    /** {@code app.base-url}: the origin {@code shortUrl} is built from. */
    public static final String BASE_URL_KEY = "app.base-url";

    /** {@code app.links.default-ttl}: default expiry when the caller does not give one. */
    public static final String DEFAULT_TTL_KEY = "app.links.default-ttl";

    /** {@code app.session.ttl}: session token lifetime. */
    public static final String SESSION_TTL_KEY = "app.session.ttl";

    /** {@code app.cache.ttl}: resolution cache lifetime; the floor under the takedown bound. */
    public static final String CACHE_TTL_KEY = "app.cache.ttl";

    /** {@code app.rate-limit.enabled}. */
    public static final String RATE_LIMIT_ENABLED_KEY = "app.rate-limit.enabled";

    /** {@code app.rate-limit.click-per-minute}: successful clicks, keyed by client IP. */
    public static final String CLICK_LIMIT_KEY = "app.rate-limit.click-per-minute";

    /** {@code app.rate-limit.not-found-per-minute}: the enumeration bucket. */
    public static final String NOT_FOUND_LIMIT_KEY = "app.rate-limit.not-found-per-minute";

    /** {@code app.rate-limit.write-per-minute}: writes, keyed by customer id. */
    public static final String WRITE_LIMIT_KEY = "app.rate-limit.write-per-minute";

    /** {@code app.rate-limit.abuse-report-per-minute}: reports, keyed by reporter. */
    public static final String ABUSE_REPORT_LIMIT_KEY = "app.rate-limit.abuse-report-per-minute";

    /** {@code app.rate-limit.sign-in-per-minute}: sign-in attempts, keyed by client IP. */
    public static final String SIGN_IN_LIMIT_KEY = "app.rate-limit.sign-in-per-minute";

    /** {@code app.threat.fail-open}: what the create path does when the checker cannot answer. */
    public static final String THREAT_FAIL_OPEN_KEY = "app.threat.fail-open";

    /**
     * The one cache name in the application, from the frozen
     * {@link CacheConfig}. The contract allows exactly one {@code CacheManager}
     * bean, which is what lets this harness clear the resolution cache without
     * knowing how it is implemented.
     */
    public static final String LINKS_CACHE = CacheConfig.LINKS_CACHE;

    // ---- wire values ------------------------------------------------------

    /** Header names the click path's contract is written in terms of. */
    public static final String CACHE_CONTROL = "Cache-Control";

    /** {@code Pragma}, for intermediaries that predate {@code Cache-Control}. */
    public static final String PRAGMA = "Pragma";

    /** {@code Expires}. */
    public static final String EXPIRES = "Expires";

    /** {@code Location}. */
    public static final String LOCATION = "Location";

    /** {@code Retry-After}, in whole seconds and never 0. */
    public static final String RETRY_AFTER = "Retry-After";

    /** {@code WWW-Authenticate}. */
    public static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    /** The exact value the click path must send on both 302 and 404. */
    public static final String NO_STORE = "no-store, no-cache, must-revalidate, max-age=0";

    /** The single not-found body, byte for byte, on both surfaces. */
    public static final String NOT_FOUND_BODY = "{\"error\":\"not_found\",\"message\":\"Not found\"}";

    /**
     * A syntactically plausible bearer value that this service did not issue and
     * cannot have signed. Used to show that a forged credential is refused with
     * the same body as no credential at all.
     */
    public static final String FORGED_BEARER =
            "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEifQ.bm90LWEtc2ln";

    /** A bearer value that is not even a JWS. */
    public static final String MALFORMED_BEARER = "this-is-not-a-jws";
}
