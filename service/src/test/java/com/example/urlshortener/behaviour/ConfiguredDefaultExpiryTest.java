package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * That the default lifetime of a link is configuration and not code (AC11).
 *
 * <p>The production default is thirty days, which no test can sit through, so
 * this class runs against its own context with {@code app.links.default-ttl} set
 * to three seconds. Two things follow from that and both are worth pinning: the
 * default a link is given tracks the property, and a link left to reach that
 * default stops redirecting when it does.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = Fixtures.DEFAULT_TTL_KEY + "=PT3S")
class ConfiguredDefaultExpiryTest extends AbstractIntegrationTest {

    /**
     * A link created without an explicit expiry expires the configured interval
     * after creation, not thirty days after it - the number comes from
     * configuration, so changing a customer's default is a data change.
     *
     * <p>Demonstrates: AC11, AC10.
     */
    @Test
    void theDefaultExpiryFollowsTheConfiguredInterval() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A link that reaches its configured default expiry stops redirecting, with
     * the single 404, and nothing has to sweep for that to be true.
     *
     * <p>Demonstrates: AC10, AC11.
     */
    @Test
    void aLinkStopsRedirectingWhenTheConfiguredDefaultPasses() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
