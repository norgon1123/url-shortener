package com.example.urlshortener.support;

import com.example.urlshortener.api.LinkResponse;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for black-box integration tests.
 *
 * <p>This is the harness, and it is harness on purpose: the {@code author-tests}
 * node writes every assertion in this suite, blind, against the frozen contract
 * -- it never sees the implementation -- and the {@code implement} node's path
 * allowlist forbids {@code src/test/**} entirely, so the agent producing the code
 * is structurally incapable of weakening the tests that gate it (ADR-003). What
 * that separation cannot survive is two authors inventing two different ways to
 * reach the service, so every such mechanism is settled here and nowhere else.
 *
 * <p>Subclasses get one HTTP client and real backing services. Driving the
 * service over HTTP is what makes blind authoring possible at all: a test written
 * against the contract's URLs and status codes compiles and runs regardless of
 * how the implementation is structured internally, whereas a unit test against a
 * service class would need class names that do not exist when the test is
 * written.
 *
 * <p>There is deliberately no {@code TestRestTemplate} here any more. Two clients
 * is two behaviours on the questions this contract actually turns on -- whether a
 * 302 is followed, and whether a 404 throws -- and the click path's response is
 * the thing under test. {@link ApiClient} is the single client; it never follows a
 * redirect and never throws on a status.
 *
 * <p>The containers are JVM-wide singletons owned by {@link TestInfrastructure};
 * see that class for why they are not managed by JUnit's {@code @Testcontainers}
 * extension. They are re-declared as fields here because
 * {@code @ServiceConnection} is discovered on the fields of the test class
 * hierarchy, and that is what wires the datasource and the Redis connection into
 * the context with no {@code @DynamicPropertySource} anywhere.
 *
 * <p><strong>Overriding configuration.</strong> A subclass that needs different
 * properties -- a bucket small enough to exhaust, an expiry short enough to sit
 * through -- annotates itself with the whole annotation again:
 *
 * <pre>{@code
 * @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
 *                 properties = Fixtures.NOT_FOUND_LIMIT_KEY + "=5")
 * class SomethingTest extends AbstractIntegrationTest { }
 * }</pre>
 *
 * The {@code webEnvironment} has to be repeated: the most specific
 * {@code @SpringBootTest} wins outright rather than merging with this one, and
 * losing {@code RANDOM_PORT} silently produces a mock environment in which
 * nothing is listening. Each distinct property set gets its own cached
 * application context; the containers are shared across all of them, so the cost
 * is one context start, not one database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = TestInfrastructure.POSTGRES;

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = TestInfrastructure.REDIS;

    /** Port the embedded server picked for this context. */
    @LocalServerPort
    protected int port;

    /**
     * The single {@code CacheManager} the contract permits. Injected so the
     * harness can clear the resolution cache without knowing how it is
     * implemented; two candidate beans would fail context load for every test at
     * once and read like a broken harness.
     */
    @Autowired
    protected CacheManager cacheManager;

    /**
     * The shared tier that holds the click deltas, the resolution cache and the
     * token buckets. Injected only so a test that needs an empty token bucket can
     * get one; see {@link #resetSharedTierState()}.
     */
    @Autowired
    protected RedisConnectionFactory redisConnectionFactory;

    /** The only way a test reaches the service. */
    protected ApiClient api;

    @BeforeEach
    void openApiClient() {
        api = new ApiClient("http://localhost:" + port);
    }

    /**
     * Proves the wiring itself: containers start, Flyway migrates, the context
     * loads. When this fails, no other failure in the suite means anything, so it
     * is worth being able to tell apart at a glance.
     */
    @Test
    void theApplicationContextLoads() {
        org.junit.jupiter.api.Assertions.assertTrue(POSTGRES.isRunning() && REDIS.isRunning());
    }

    // ---- sessions ---------------------------------------------------------

    /** A session for the first seeded customer. */
    protected String alice() {
        return api.signInFor(Fixtures.ALICE);
    }

    /** A session for the second seeded customer, who owns nothing the first one owns. */
    protected String bob() {
        return api.signInFor(Fixtures.BOB);
    }

    // ---- creating links a test can act on ---------------------------------

    /**
     * The ordinary precondition: one live link, owned by the given customer,
     * pointing at {@link Fixtures#TARGET_URL}, with the default expiry.
     *
     * <p>Returns the parsed {@link LinkResponse} rather than the HTTP response,
     * because a test that says "given a link" is not testing creation. Tests that
     * <em>are</em> testing creation call {@link ApiClient#createLink} directly and
     * keep the raw response.
     */
    protected LinkResponse givenLink(String bearer) {
        return givenLink(bearer, Fixtures.TARGET_URL);
    }

    /** As {@link #givenLink(String)}, pointing at a target of the caller's choosing. */
    protected LinkResponse givenLink(String bearer, String longUrl) {
        return ApiClient.asLink(api.createLink(bearer, longUrl));
    }

    /** A live link whose code is the given alias. */
    protected LinkResponse givenLinkWithAlias(String bearer, String alias) {
        return ApiClient.asLink(api.createLink(bearer, Fixtures.TARGET_URL, alias, null));
    }

    /**
     * A link that expires {@code ttl} from now.
     *
     * <p>Pair with {@link #awaitExpiry(LinkResponse)} to observe an expired link.
     */
    protected LinkResponse givenLinkExpiringIn(String bearer, Duration ttl) {
        return ApiClient.asLink(
                api.createLink(bearer, Fixtures.TARGET_URL, null, Instant.now().plus(ttl)));
    }

    /**
     * A link of this customer's that is already expired.
     *
     * <p><strong>How expiry is reached, and why.</strong> There is no way to
     * create a link in the past -- a past {@code expiresAt} is a 400 on both
     * create and patch, deliberately, because {@code DELETE} is the takedown --
     * and this harness does not reach into the database, whose schema is not part
     * of the frozen contract. So a short expiry is set through the API and the
     * harness sits through it: {@link Fixtures#SHORT_EXPIRY} is three seconds, and
     * only the handful of tests that need an expired link pay it. Status is
     * derived from {@code expires_at} at read time and never stored, so nothing
     * has to run for the link to become expired; the cache is cleared anyway so
     * that a cached row cannot be the reason a test passes.
     */
    protected LinkResponse givenExpiredLink(String bearer) {
        LinkResponse link = givenLinkExpiringIn(bearer, Fixtures.SHORT_EXPIRY);
        awaitExpiry(link);
        return link;
    }

    /** A link of this customer's that they have deleted. */
    protected LinkResponse givenDeletedLink(String bearer) {
        LinkResponse link = givenLink(bearer);
        api.deleteLink(bearer, link.code());
        return link;
    }

    /**
     * A link of {@code owner}'s that another signed-in customer has reported, and
     * which is therefore blocked. The reporter is the other seeded customer, since
     * an abuse report may name any link and takedown must not be reversible by the
     * link's owner.
     */
    protected LinkResponse givenBlockedLink(String owner, String reporter) {
        LinkResponse link = givenLink(owner);
        api.reportAbuse(reporter, link.code(), "Phishing page imitating a bank sign-in");
        return link;
    }

    // ---- time -------------------------------------------------------------

    /**
     * Blocks until the link's own {@code expiresAt} has passed, with a small
     * margin for clock skew between this JVM and the service, then clears the
     * resolution cache.
     */
    protected void awaitExpiry(LinkResponse link) {
        awaitInstant(link.expiresAt().plusMillis(500));
        evictResolutionCache();
    }

    /** Blocks until the given instant, or returns immediately if it has passed. */
    protected void awaitInstant(Instant when) {
        long millis = Duration.between(Instant.now(), when).toMillis();
        if (millis > 0) {
            sleep(Duration.ofMillis(millis));
        }
    }

    /** Uninterruptible-looking sleep, so a test never has to handle the checked exception. */
    protected void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(0L, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }

    /**
     * Polls {@code condition} until it holds and reports how long that took, or
     * empty if it never held within {@code limit}.
     *
     * <p>This is how a published bound is measured rather than assumed: a delete
     * or an abuse report must stop the link redirecting within
     * {@link Fixtures#TAKEDOWN_BOUND} of the response, and the elapsed time is the
     * evidence. The harness reports the number; deciding what the number has to be
     * is the assertion, and belongs to the test.
     */
    protected Optional<Duration> observeUntil(BooleanSupplier condition, Duration limit) {
        Instant start = Instant.now();
        while (true) {
            if (condition.getAsBoolean()) {
                return Optional.of(Duration.between(start, Instant.now()));
            }
            if (Duration.between(start, Instant.now()).compareTo(limit) >= 0) {
                return Optional.empty();
            }
            sleep(Duration.ofMillis(250));
        }
    }

    // ---- caches -----------------------------------------------------------

    /**
     * Clears the redirect resolution cache, including its negative entries.
     *
     * <p>Use it to remove a cache as the <em>explanation</em> for a result, never
     * as the way a takedown takes effect: the contract requires deletes, expiry
     * changes and takedowns to invalidate actively, and a test that cleared the
     * cache itself before looking would pass against an implementation that never
     * invalidated anything and would breach the published bound in production.
     */
    protected void evictResolutionCache() {
        Cache cache = cacheManager.getCache(Fixtures.LINKS_CACHE);
        if (cache != null) {
            cache.clear();
        }
    }

    /**
     * Empties the shared tier: every token bucket, every cache entry and every
     * click delta that has not been written down yet.
     *
     * <p>Token buckets are keyed by client IP and by customer id, and one Redis is
     * shared by every context in the JVM, so a bucket a rate-limit test emptied is
     * still empty when the next rate-limit test looks at it a second later. A test
     * that needs to observe a limit being reached therefore starts from a known
     * state rather than from whatever the previous class left behind.
     *
     * <p>Do not call this from a counting test. It discards deltas that have not
     * been flushed, which is precisely the thing AC3 says must not be lost, and a
     * test that threw them away itself would be proving nothing.
     */
    protected void resetSharedTierState() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    // ---- clicks in bulk ---------------------------------------------------

    /** Clicks a code {@code times} in a row from this one client, sequentially. */
    protected List<HttpResponse<String>> clickRepeatedly(String code, int times) {
        List<HttpResponse<String>> responses = new ArrayList<>(times);
        for (int i = 0; i < times; i++) {
            responses.add(api.click(code));
        }
        return responses;
    }

    /**
     * Clicks a code {@code times} from {@code threads} concurrent callers and
     * returns every response.
     *
     * <p>This is the shape of the traffic AC22 describes and the only way to show
     * that a counter is exact rather than approximately right: a count that is
     * correct sequentially and lossy under concurrency is the defect worth
     * catching, and it does not appear one click at a time.
     */
    protected List<HttpResponse<String>> clickConcurrently(String code, int times, int threads) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<HttpResponse<String>>> pending = new ArrayList<>(times);
            for (int i = 0; i < times; i++) {
                pending.add(pool.submit(() -> api.click(code)));
            }
            List<HttpResponse<String>> responses = new ArrayList<>(times);
            for (Future<HttpResponse<String>> future : pending) {
                responses.add(future.get());
            }
            return responses;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during a click burst", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("a click in the burst failed to complete", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** The click count this service currently reports for one of the caller's links. */
    protected long reportedClickCount(String bearer, String code) {
        return ApiClient.asLink(api.getLink(bearer, code)).clickCount();
    }
}
