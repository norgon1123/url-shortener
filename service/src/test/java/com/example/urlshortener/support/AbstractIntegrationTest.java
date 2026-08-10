package com.example.urlshortener.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for black-box integration tests.
 *
 * <p>This is scaffold, and it is scaffold on purpose: it is the only piece of
 * {@code src/test/**} written by hand. The {@code author-tests} node writes
 * every actual test, blind, against the frozen OpenAPI contract -- it never sees
 * the implementation -- and the {@code implement} node's path allowlist forbids
 * {@code src/test/**} entirely, so the agent producing the code is structurally
 * incapable of weakening the tests that gate it (ADR-003).
 *
 * <p>Subclasses get an HTTP client and a real PostgreSQL. Both matter. Driving
 * the service over HTTP is what makes blind authoring possible at all: a test
 * written against the contract's URLs and status codes compiles and runs
 * regardless of how the implementation is structured internally, whereas a unit
 * test against a service class would need to know class names that do not exist
 * when the test is written.
 *
 * <p>One PostgreSQL is shared by the whole JVM -- the singleton-container
 * pattern, started in a static initialiser and deliberately <em>not</em>
 * managed by JUnit.
 *
 * <p>The obvious spelling, {@code @Testcontainers} with {@code @Container} on
 * this field, does the opposite of what it reads like here. That extension ties
 * the container's lifetime to the test class it is declared on, so it is stopped
 * when the first subclass finishes; every later class inherits the same dead
 * static field while Spring's cached context still points at the old port, and
 * each request then waits out a thirty-second connection timeout. The suite does
 * not fail — it crawls, which is far harder to diagnose than a failure. That is
 * what pushed this project's first full {@code verify} past a thirty-minute
 * ceiling.
 *
 * <p>Started once here, the container outlives every test class, and
 * Testcontainers' own reaper removes it when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestRestTemplate http;

    /**
     * Proves the wiring itself: containers start, Flyway migrates, the context
     * loads. When this fails, no other failure in the suite means anything, so
     * it is worth being able to tell apart at a glance.
     */
    @Test
    void theApplicationContextLoads() {
        org.junit.jupiter.api.Assertions.assertTrue(POSTGRES.isRunning());
    }
}
