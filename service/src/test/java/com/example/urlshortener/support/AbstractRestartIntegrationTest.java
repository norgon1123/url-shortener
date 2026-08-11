package com.example.urlshortener.support;

import com.example.urlshortener.UrlShortenerApplication;
import com.example.urlshortener.api.LinkResponse;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Base class for the tests that need the application to go away and come back.
 *
 * <p>Some of what this contract promises is about durability rather than about a
 * response: a click count is exact "for the life of the link", and a link that
 * exists survives a deploy. Neither claim means anything if it is only ever
 * observed inside one process lifetime, and an in-memory implementation would
 * satisfy every other test in this suite.
 *
 * <p><strong>Why this does not extend {@link AbstractIntegrationTest}.</strong>
 * The Spring TestContext framework owns the context in a {@code @SpringBootTest};
 * closing it mid-test poisons the cached context for every later class, and
 * {@code @DirtiesContext} only acts between tests, never inside one. So this
 * harness boots the application itself with a {@link SpringApplicationBuilder}
 * and keeps the handle. It exists here, once, because the last time a suite was
 * written without it the tests invented a restart the harness did not support and
 * the failure only surfaced after everything had been built and merged.
 *
 * <p>The containers are <em>not</em> restarted. They are the JVM-wide singletons
 * from {@link TestInfrastructure} and are passed in as ordinary properties, so
 * what the restart changes is the application process and nothing else. Restarting
 * the database as well would prove nothing about the service.
 *
 * <p>Each test method gets a freshly booted application on a fresh random port,
 * and it is stopped afterwards whether the test passed or not. The database is
 * shared with the rest of the suite, so a test here works with the links it
 * created rather than with everything it can see.
 */
public abstract class AbstractRestartIntegrationTest {

    private ConfigurableApplicationContext context;

    /** Points at whichever application process is currently running. */
    protected ApiClient api;

    @BeforeEach
    void bootApplication() {
        start();
    }

    @AfterEach
    void shutDownApplication() {
        stop();
    }

    /**
     * Stops the application and starts a new one against the same PostgreSQL and
     * the same Redis, then repoints {@link #api} at it.
     *
     * <p>Everything held only in memory is gone afterwards, which is the point:
     * whatever a test can still observe after this call is genuinely durable.
     * Sessions issued before the restart may or may not survive it -- the signing
     * key is ephemeral when none is configured -- so a test that needs a session
     * after a restart signs in again.
     */
    protected void restartApplication() {
        stop();
        start();
    }

    /** A session for the first seeded customer, against the running process. */
    protected String alice() {
        return api.signInFor(Fixtures.ALICE);
    }

    /** A session for the second seeded customer, against the running process. */
    protected String bob() {
        return api.signInFor(Fixtures.BOB);
    }

    /** One live link owned by the given customer. */
    protected LinkResponse givenLink(String bearer) {
        return ApiClient.asLink(api.createLink(bearer, Fixtures.TARGET_URL));
    }

    /**
     * One live link owned by nobody, created with no credential.
     *
     * <p>Present here as well as on {@link AbstractIntegrationTest} because an
     * anonymous link is a row like any other and the durability question applies
     * to it too: it must still redirect after the process that minted it is gone,
     * and its fixed expiry must survive with it. An implementation that held
     * anonymous links in memory - tempting, since nothing ever reads them back -
     * would satisfy every other test in this suite.
     */
    protected com.example.urlshortener.api.AnonymousLinkResponse givenAnonymousLink() {
        HttpResponse<String> response = api.createAnonymousLink(Fixtures.TARGET_URL);
        if (response.statusCode() != 201) {
            throw new IllegalStateException(
                    "could not create an anonymous link: HTTP " + response.statusCode()
                            + " " + response.body());
        }
        return ApiClient.asAnonymousLink(response);
    }

    /**
     * An account created against the running process, with an address nobody has
     * taken.
     *
     * <p>A session obtained before a restart may or may not verify after one - the
     * signing key is ephemeral when none is configured - so a test that needs a
     * session after the restart signs in again with {@link #sessionFor}. That is
     * exactly the AC5 claim worth making durable: the account, not the token,
     * is what has to survive.
     */
    protected Fixtures.NewAccount givenAccount() {
        String email = Fixtures.uniqueEmail("carol");
        HttpResponse<String> response = api.signUp(email, Fixtures.NEW_ACCOUNT_PASSWORD);
        if (response.statusCode() != 201) {
            throw new IllegalStateException(
                    "could not create the account " + email + ": HTTP " + response.statusCode()
                            + " " + response.body());
        }
        return new Fixtures.NewAccount(
                ApiClient.asAccount(response).customerId(), email, Fixtures.NEW_ACCOUNT_PASSWORD);
    }

    /** A session for an account this suite created, against the running process. */
    protected String sessionFor(Fixtures.NewAccount account) {
        return api.sessionFor(account.email(), account.password());
    }

    /** Clicks a code the given number of times, sequentially, and returns nothing but the count attempted. */
    protected List<Integer> clickRepeatedly(String code, int times) {
        List<Integer> statuses = new ArrayList<>(times);
        for (int i = 0; i < times; i++) {
            statuses.add(api.click(code).statusCode());
        }
        return statuses;
    }

    /** Uninterruptible-looking sleep, for waiting out a flush interval. */
    protected void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(0L, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e);
        }
    }

    private void start() {
        context = new SpringApplicationBuilder(UrlShortenerApplication.class).run(commandLineArguments());
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        api = new ApiClient("http://localhost:" + port);
    }

    /**
     * The container coordinates and the port, as command-line arguments.
     *
     * <p>Deliberately not {@link SpringApplicationBuilder#properties(String...)}.
     * That populates Boot's {@code defaultProperties}, which is the
     * <em>lowest</em>-precedence property source of all - below
     * {@code application.yml}, whose
     * {@code spring.datasource.url: ${DB_URL:jdbc:postgresql://localhost:5432/shortener}}
     * and {@code server.port: ${SERVER_PORT:8080}} therefore win. The application
     * then boots against a database that is not there and every test in the class
     * errors in Flyway before it runs. {@code application.yml} is frozen and
     * protected, so the harness is what has to give: command-line arguments sit
     * above it, so these are the values the restarted process actually uses.
     */
    private static String[] commandLineArguments() {
        String[] properties = TestInfrastructure.springProperties();
        String[] arguments = new String[properties.length + 1];
        for (int i = 0; i < properties.length; i++) {
            arguments[i] = "--" + properties[i];
        }
        arguments[properties.length] = "--server.port=0";
        return arguments;
    }

    private void stop() {
        if (context != null) {
            context.close();
            context = null;
        }
    }
}
