package com.example.urlshortener.support;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The backing services every test in this suite shares, and the only place that
 * decides how they are started, addressed and taken away.
 *
 * <p>Both containers are JVM-wide singletons started in a static initialiser and
 * deliberately <em>not</em> managed by JUnit's {@code @Testcontainers}
 * extension. That extension ties a container's lifetime to the class it is
 * declared on, so the container is stopped when the first test class finishes
 * while every later class inherits the same dead static field and Spring's
 * cached context still points at the dead port; the suite does not fail, it
 * crawls behind connection timeouts, which is far harder to diagnose than a
 * failure. Started here, they outlive every test class and Testcontainers' own
 * reaper removes them when the JVM exits.
 *
 * <p>They live in this class rather than on the base test class because two
 * different harnesses need them: {@link AbstractIntegrationTest}, which lets
 * Spring Boot wire them in with {@code @ServiceConnection}, and
 * {@link AbstractRestartIntegrationTest}, which boots the application itself and
 * therefore has to pass the coordinates as ordinary properties. A restart test
 * that restarted the database as well would be proving nothing about the
 * service.
 *
 * <p>Redis is a plain {@link GenericContainer}. {@code spring-boot-testcontainers}
 * ships a connection-details factory keyed on the image name {@code redis}, so no
 * third-party Testcontainers module is needed and none is on the frozen
 * {@code pom.xml}.
 */
public final class TestInfrastructure {

    /** The port Redis listens on inside its container. */
    public static final int REDIS_PORT = 6379;

    /**
     * Connection slots the shared database offers, well above the sum of every
     * pool the suite holds open at once.
     *
     * <p>This number is the suite's connection budget and it is arithmetic, not
     * taste. Each distinct {@code @SpringBootTest} property set gets its own
     * cached application context (see {@link AbstractIntegrationTest}), Spring
     * keeps those contexts for the lifetime of the JVM -- surefire runs one fork
     * -- and every one of them holds a HikariCP pool at the Spring Boot default
     * maximum of ten connections, plus the applications
     * {@link AbstractRestartIntegrationTest} boots itself. At PostgreSQL's stock
     * {@code max_connections=100} that ceiling is reached at the tenth context,
     * and the eleventh fails at Flyway with {@code FATAL: sorry, too many clients
     * already} -- a whole test class erroring in context startup, before any test
     * body runs, for a reason that has nothing to do with what it asserts and
     * that lands on whichever class happens to sort last.
     *
     * <p>Raising the ceiling here rather than shrinking the pools keeps the
     * suite's concurrency behaviours honest: several of them
     * (racing sign-ups, concurrent clicks) depend on requests genuinely
     * overlapping, and a pool small enough to queue them would turn a real race
     * into a queue and pass against an implementation that has none.
     */
    public static final int MAX_CONNECTIONS = 500;

    /**
     * Real PostgreSQL, never an in-memory dialect: this service leans on a
     * unique constraint for code collisions and on timestamp comparison for
     * expiry, and a fake dialect would fake exactly the behaviour under test.
     *
     * <p>Started with a raised {@code max_connections}; see
     * {@link #MAX_CONNECTIONS} for the arithmetic that forces it.
     */
    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withCommand("postgres", "-c", "max_connections=" + MAX_CONNECTIONS);

    /**
     * Real Redis: the click counters, the resolution cache and the token buckets
     * all live there, and all three are load-bearing for acceptance criteria that
     * this suite has to be able to observe.
     */
    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private TestInfrastructure() {
    }

    /**
     * Connection coordinates in {@code key=value} form, for a harness that starts
     * the application outside the Spring TestContext framework and so cannot use
     * {@code @ServiceConnection}.
     */
    public static String[] springProperties() {
        return new String[] {
            "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword(),
            "spring.data.redis.host=" + REDIS.getHost(),
            "spring.data.redis.port=" + REDIS.getMappedPort(REDIS_PORT),
        };
    }

    /**
     * Makes Redis stop answering without changing its address.
     *
     * <p>Docker's pause is used rather than {@code stop()} on purpose: a stopped
     * container comes back on a different mapped port, which the running
     * application would never learn about, so the "recovered" half of a
     * degradation test could not be observed. A paused container keeps its port
     * mapping and its data, so the outage is reversible and the service sees
     * exactly what a network partition looks like.
     *
     * <p>This is the mechanism behind every AC20 behaviour: the click path is
     * required to keep serving while the tier that counts clicks and limits
     * requests is unreachable.
     */
    public static void pauseCounterTier() {
        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    /** Undoes {@link #pauseCounterTier()}; safe to call when not paused. */
    public static void resumeCounterTier() {
        try {
            DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
        } catch (RuntimeException alreadyRunning) {
            // Unpausing a running container is not an error worth failing a test for.
        }
    }

    /**
     * Runs {@code body} with Redis unreachable, and restores it whatever happens.
     *
     * <p>Always use this rather than pausing by hand: a test that leaves the
     * container paused takes down every later test class in the JVM, and the
     * resulting failures point everywhere except at the test that caused them.
     */
    public static void withCounterTierUnavailable(Outage body) {
        pauseCounterTier();
        try {
            body.run();
        } finally {
            resumeCounterTier();
        }
    }

    /** Body of a {@link #withCounterTierUnavailable(Outage)} block. */
    @FunctionalInterface
    public interface Outage {
        void run();
    }
}
