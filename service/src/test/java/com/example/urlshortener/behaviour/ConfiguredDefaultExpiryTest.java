package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.domain.LinkStatus;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    /** What this context's {@code app.links.default-ttl} is set to, above. */
    private static final Duration CONFIGURED_TTL = Duration.ofSeconds(3);

    /**
     * A link created without an explicit expiry expires the configured interval
     * after creation, not thirty days after it - the number comes from
     * configuration, so changing a customer's default is a data change.
     *
     * <p>Demonstrates: AC11, AC10.
     */
    @Test
    void theDefaultExpiryFollowsTheConfiguredInterval() {
        HttpResponse<String> created = api.createLink(alice(), Fixtures.TARGET_URL);

        assertEquals(201, created.statusCode(), created.body());
        LinkResponse link = ApiClient.asLink(created);
        Duration granted = Duration.between(link.createdAt(), link.expiresAt());
        assertAll(
                () -> assertTrue(
                        granted.minus(CONFIGURED_TTL).abs().compareTo(Duration.ofSeconds(1)) <= 0,
                        "the default expiry must follow app.links.default-ttl, but this link was given "
                                + granted),
                () -> assertTrue(
                        granted.compareTo(Fixtures.DEFAULT_LINK_TTL) < 0,
                        "a default welded into the code would ignore the property and grant "
                                + Fixtures.DEFAULT_LINK_TTL));
    }

    /**
     * A link that reaches its configured default expiry stops redirecting, with
     * the single 404, and nothing has to sweep for that to be true.
     *
     * <p>Demonstrates: AC10, AC11.
     */
    @Test
    void aLinkStopsRedirectingWhenTheConfiguredDefaultPasses() {
        String alice = alice();
        LinkResponse link = givenLink(alice);
        assertEquals(302, api.click(link.code()).statusCode(), "it redirected while it was live");

        // Sits through the link's own expiresAt. Status is derived from that
        // timestamp at read time, so nothing has to have run for this to hold.
        awaitExpiry(link);

        HttpResponse<String> afterExpiry = api.click(link.code());
        LinkResponse asItsOwnerSeesIt = ApiClient.asLink(api.getLink(alice, link.code()));
        assertAll(
                () -> assertEquals(404, afterExpiry.statusCode(), "an expired link does not redirect"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, afterExpiry.body()),
                () -> assertTrue(
                        ApiClient.header(afterExpiry, Fixtures.LOCATION).isEmpty(),
                        "nothing may still point at the target"),
                () -> assertEquals(LinkStatus.EXPIRED, asItsOwnerSeesIt.status(),
                        "and its owner is shown why"));
    }
}
