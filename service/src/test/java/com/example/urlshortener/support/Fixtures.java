package com.example.urlshortener.support;

import com.example.urlshortener.config.CacheConfig;
import java.time.Duration;
import java.util.List;
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

    // ======================================================================
    // Added for the sign-up, anonymous-creation and host-normalisation change.
    // Everything below is quoted from the frozen contract (artifacts/openapi.yaml,
    // AppProperties, HostNormalizer's worked table) or invented once here so that
    // two tests cannot invent it differently.
    // ======================================================================

    // ---- accounts created at run time -------------------------------------

    /**
     * An account this suite created through {@code POST /api/v1/customers},
     * carrying the plaintext the test chose so it can be signed in with
     * afterwards (AC5) and looked up in storage (AC7).
     *
     * @param id       the {@code customerId} the sign-up response returned
     * @param email    the account name, unique per test run
     * @param password the plaintext used at sign-up; never stored in this form
     */
    public record NewAccount(UUID id, String email, String password) {
    }

    /** Minimum password length accepted at sign-up, from {@code SignUpRequest}. */
    public static final int PASSWORD_MIN_LENGTH = 12;

    /** Maximum password length accepted at sign-up, from {@code SignUpRequest}. */
    public static final int PASSWORD_MAX_LENGTH = 256;

    /** An ordinary, comfortably valid password for an account a test creates. */
    public static final String NEW_ACCOUNT_PASSWORD = "correct-horse-battery-staple";

    /** Exactly {@link #PASSWORD_MIN_LENGTH} characters: the lower boundary, inclusive. */
    public static final String MIN_LENGTH_PASSWORD = "twelvechars1";

    /** One character short of the minimum: the first value outside the rule. */
    public static final String TOO_SHORT_PASSWORD = "elevenchar1";

    /** Exactly {@link #PASSWORD_MAX_LENGTH} characters: the upper boundary, inclusive. */
    public static final String MAX_LENGTH_PASSWORD = "p".repeat(PASSWORD_MAX_LENGTH);

    /** One character past the maximum. */
    public static final String TOO_LONG_PASSWORD = "p".repeat(PASSWORD_MAX_LENGTH + 1);

    /** No {@code @}, so it is not a well-formed address: the {@code fields.email} case. */
    public static final String MALFORMED_EMAIL = "carol-at-example-dot-com";

    /**
     * An address nobody has taken, unique per call.
     *
     * <p>The database outlives an individual test class in this suite, so an
     * account name is only free the first time it is used. Every test that signs
     * up draws its address from here rather than hard-coding one, otherwise the
     * second run of the same class sees 409 where it expected 201 and the failure
     * looks like a defect in uniqueness rather than in the fixture.
     */
    public static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                + "@example.test";
    }

    // ---- targets that exercise host normalisation --------------------------
    //
    // The split between "refused with 400" and "refused with 422" is not a matter
    // of taste and is the single easiest thing for a blind test author to get
    // wrong, so it is settled here, measured against java.net.URI on JDK 21:
    //
    //   * If URI.getHost() returns a host, the syntax gate passes and the host
    //     policy decides -> 422 url_rejected.
    //   * If URI.getHost() returns null (URI cannot parse a host from the
    //     authority at all), the request never reaches the host policy -> 400
    //     invalid_request. This is unchanged by the change and is the reading the
    //     design took on feasibility's U1.
    //
    // Measured hosts: "2130706433", "0x7f000001", "017700000001", "0177.0.0.1",
    // "012.0.0.1", "4294967296", "malware.example.com." all parse; "127.1",
    // "0x7f.0.0.1", "999.999.999.999", "1.2.3.4.5", "a..b" all yield a null host.

    /** AC1 verbatim: the denylisted host written with a trailing dot. */
    public static final String DENYLISTED_TRAILING_DOT_URL = "https://malware.example.com./x";

    /** The same host in mixed case and with a trailing dot. */
    public static final String DENYLISTED_MIXED_CASE_TRAILING_DOT_URL =
            "https://MALWARE.Example.COM./x";

    /** A sub-domain of a denylisted host: refused today and must stay refused. */
    public static final String DENYLISTED_SUBDOMAIN_URL =
            "https://sub.campaign.malware.example.com/x";

    /** The second denylist entry with a trailing dot. */
    public static final String PHISHING_TRAILING_DOT_URL = "https://phishing.example.net./signin";

    /**
     * A host that merely <em>contains</em> a denylisted host as a suffix of one
     * label. Normalisation must never drop or split a label, so this stays
     * acceptable; a check that collapsed it onto the denylisted parent would be
     * the over-normalisation failure the design calls the most dangerous one here.
     */
    public static final String LOOKALIKE_HOST_URL = "https://notmalware.example.com/x";

    /**
     * A host whose <em>left</em> labels spell a denylisted host but which is a
     * different domain entirely. Also acceptable, for the same reason.
     */
    public static final String DENYLISTED_HOST_AS_PREFIX_URL =
            "https://malware.example.com.evil.test/x";

    /** AC2 verbatim: loopback as a single decimal number. Host parses, so 422. */
    public static final String LOOPBACK_DECIMAL_URL = "http://2130706433/";

    /** Loopback in hexadecimal. Host parses, so 422. */
    public static final String LOOPBACK_HEX_URL = "http://0x7f000001/";

    /** Loopback as one long octal number. Host parses, so 422. */
    public static final String LOOPBACK_LONG_OCTAL_URL = "http://017700000001/";

    /**
     * Loopback with an octal first part. Host parses, so 422 - and this is the
     * form {@code InetAddress.getByName} canonicalises to the public 177.0.0.1,
     * which is why the contract hand-parses it.
     */
    public static final String LOOPBACK_DOTTED_OCTAL_URL = "http://0177.0.0.1/";

    /** Private address space with an octal first part: canonicalises to 10.0.0.1, so 422. */
    public static final String PRIVATE_DOTTED_OCTAL_URL = "http://012.0.0.1/";

    /** The loopback name with a trailing dot. Host parses, so 422. */
    public static final String LOOPBACK_TRAILING_DOT_URL = "http://localhost./internal";

    /** IPv4-mapped IPv6 loopback: refused today, must stay refused. Host parses, so 422. */
    public static final String LOOPBACK_IPV6_MAPPED_URL = "http://[::ffff:127.0.0.1]/internal";

    /**
     * Numerically out of range but syntactically a host {@code URI} accepts, so it
     * reaches the host policy and fails closed there: 422, never accepted as a
     * name.
     */
    public static final String OVERFLOW_NUMERIC_URL = "http://4294967296/";

    /** Short numeric form. {@code URI} yields no host, so this is the 400 side of the split. */
    public static final String UNPARSEABLE_SHORT_NUMERIC_URL = "http://127.1/";

    /** Dotted hexadecimal. No host from {@code URI}: 400. */
    public static final String UNPARSEABLE_DOTTED_HEX_URL = "http://0x7f.0.0.1/";

    /** Out of range in every part. No host from {@code URI}: 400. */
    public static final String UNPARSEABLE_OUT_OF_RANGE_NUMERIC_URL = "http://999.999.999.999/";

    /** Five numeric parts. No host from {@code URI}: 400. */
    public static final String UNPARSEABLE_FIVE_PART_NUMERIC_URL = "http://1.2.3.4.5/";

    /** An empty label. No host from {@code URI}: 400. */
    public static final String UNPARSEABLE_EMPTY_LABEL_URL = "http://a..b/";

    /**
     * Every equivalent-form spelling that reaches the host policy and must be
     * refused there with 422 {@code url_rejected} - on the authenticated create
     * path (AC1, AC2) and on the anonymous one (AC12), identically.
     */
    public static final List<String> EQUIVALENT_FORM_URLS_REFUSED_AS_UNSHORTENABLE = List.of(
            DENYLISTED_TRAILING_DOT_URL,
            DENYLISTED_MIXED_CASE_TRAILING_DOT_URL,
            PHISHING_TRAILING_DOT_URL,
            LOOPBACK_DECIMAL_URL,
            LOOPBACK_HEX_URL,
            LOOPBACK_LONG_OCTAL_URL,
            LOOPBACK_DOTTED_OCTAL_URL,
            PRIVATE_DOTTED_OCTAL_URL,
            LOOPBACK_TRAILING_DOT_URL,
            LOOPBACK_IPV6_MAPPED_URL,
            OVERFLOW_NUMERIC_URL);

    /**
     * Spellings {@code java.net.URI} cannot extract a host from. They are refused
     * before the host policy runs and keep the status they have today; the change
     * must not move them.
     */
    public static final List<String> URLS_REFUSED_AS_MALFORMED = List.of(
            UNPARSEABLE_SHORT_NUMERIC_URL,
            UNPARSEABLE_DOTTED_HEX_URL,
            UNPARSEABLE_OUT_OF_RANGE_NUMERIC_URL,
            UNPARSEABLE_FIVE_PART_NUMERIC_URL,
            UNPARSEABLE_EMPTY_LABEL_URL);

    /**
     * Targets that are close to a refused one but are not it. Tightening the check
     * must not sweep these up: a normalisation that dropped or merged a label
     * would silently stop customers shortening ordinary URLs, and no acceptance
     * criterion would notice.
     */
    public static final List<String> LOOKALIKE_URLS_STILL_ACCEPTED = List.of(
            LOOKALIKE_HOST_URL,
            DENYLISTED_HOST_AS_PREFIX_URL);

    // ---- hosts, for the normaliser's own contract ---------------------------
    //
    // HostNormalizer.normalize is a frozen static method with a worked table in
    // its javadoc; these quote that table so the unit-level behaviours and the
    // HTTP-level ones cannot disagree about what canonical means.

    /** Hosts that canonicalise to the loopback dotted quad. */
    public static final List<String> LOOPBACK_HOST_SPELLINGS =
            List.of("2130706433", "0x7f000001", "017700000001", "0177.0.0.1", "127.1", "127.0.0.1");

    /** The canonical form of every entry in {@link #LOOPBACK_HOST_SPELLINGS}. */
    public static final String CANONICAL_LOOPBACK_HOST = "127.0.0.1";

    /** Hosts that are IPv4 candidates but out of range: the normaliser answers empty. */
    public static final List<String> UNCANONICALISABLE_HOSTS =
            List.of("999.999.999.999", "4294967296", "a..b", ".", "..");

    /** Registered names that must survive normalisation with every label intact. */
    public static final List<String> NAMES_PRESERVED_BY_NORMALISATION =
            List.of("notmalware.example.com", "malware.example.com.evil.test", "1.2.3.4.5", "09.example.com");

    // ---- anonymous links ---------------------------------------------------

    /** "One month" for an anonymous link: {@code app.links.anonymous-ttl}, 30 days (AC10). */
    public static final Duration ANONYMOUS_LINK_TTL = Duration.ofDays(30);

    /**
     * An anonymous TTL short enough to sit through, as a property value.
     *
     * <p>The caller cannot choose an anonymous link's expiry - that is the whole
     * point of AC10 - so the only way to observe an expired one without waiting a
     * month is to configure the service's TTL down for the class that needs it.
     * See {@link #ANONYMOUS_TTL_KEY}.
     */
    public static final String SHORT_ANONYMOUS_TTL_VALUE = "PT3S";

    /** {@link #SHORT_ANONYMOUS_TTL_VALUE} as a {@link Duration}. */
    public static final Duration SHORT_ANONYMOUS_TTL = Duration.ofSeconds(3);

    // ---- configuration keys added by this change ---------------------------

    /** {@code app.links.anonymous-ttl}: lifetime of a link created with no account. */
    public static final String ANONYMOUS_TTL_KEY = "app.links.anonymous-ttl";

    /** {@code app.rate-limit.sign-up-per-minute}: account creation, keyed by client IP. */
    public static final String SIGN_UP_LIMIT_KEY = "app.rate-limit.sign-up-per-minute";

    /** {@code app.rate-limit.anonymous-create-per-minute}: anonymous creation, keyed by client IP. */
    public static final String ANONYMOUS_CREATE_LIMIT_KEY = "app.rate-limit.anonymous-create-per-minute";

    /**
     * {@code app.abuse.min-reporter-age}: how old an account must be before it may
     * report a link its own creation did not pre-date.
     *
     * <p>Self-service sign-up re-scopes the takedown path from two hand-provisioned
     * accounts to anyone on the internet, and the per-reporter bucket is keyed by
     * customer id - which an attacker now mints for themselves. The eligibility
     * rule is what bounds that, so the classes that exercise it drive this key down
     * rather than sitting out the production default.
     */
    public static final String MIN_REPORTER_AGE_KEY = "app.abuse.min-reporter-age";

    /**
     * A minimum reporter age short enough for a test to sit through, as a property
     * value. Long enough that creating an account, signing in and posting a report
     * comfortably fits inside it on a slow machine.
     */
    public static final String SHORT_MIN_REPORTER_AGE_VALUE = "PT5S";

    /** {@link #SHORT_MIN_REPORTER_AGE_VALUE} as a {@link Duration}. */
    public static final Duration SHORT_MIN_REPORTER_AGE = Duration.ofSeconds(5);

    /** {@code app.click.flush-interval}: how often click deltas are drained into PostgreSQL. */
    public static final String CLICK_FLUSH_INTERVAL_KEY = "app.click.flush-interval";

    /** The default of {@link #CLICK_FLUSH_INTERVAL_KEY}, quoted from {@code AppProperties}. */
    public static final Duration CLICK_FLUSH_INTERVAL = Duration.ofSeconds(5);

    // ---- wire values added by this change ----------------------------------

    /** The {@code error} value a duplicate account name answers with. */
    public static final String ACCOUNT_UNAVAILABLE = "account_unavailable";

    /** The {@code error} value every host-policy refusal answers with. */
    public static final String URL_REJECTED = "url_rejected";

    /** The {@code error} value a malformed or unknown-property body answers with. */
    public static final String INVALID_REQUEST = "invalid_request";

    /** The {@code error} value an empty token bucket answers with. */
    public static final String RATE_LIMITED = "rate_limited";

    /** The {@code error} value a shed dependency answers with on a create path. */
    public static final String SERVICE_UNAVAILABLE = "service_unavailable";
}
