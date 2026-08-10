package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.domain.LinkStatus;
import com.example.urlshortener.support.AbstractRestartIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * What survives the application going away and coming back.
 *
 * <p>"For the life of the link" (AC3) is a claim about durability, and every
 * other test in this suite would pass against an implementation that kept links
 * and counts in a map. This class is the one that would not: it boots the
 * application, acts, restarts the process against the same database and the same
 * Redis, and looks again.
 *
 * <p>The restart mechanism lives in {@link AbstractRestartIntegrationTest} and
 * nowhere else. Sessions are re-obtained after a restart rather than reused: the
 * signing key is ephemeral when none is configured, so surviving credentials are
 * not something this contract promises.
 */
class DurabilityAcrossRestartTest extends AbstractRestartIntegrationTest {

    /**
     * A link created before a restart still exists afterwards and still redirects
     * to the same target with the same code.
     *
     * <p>Demonstrates: AC2, AC1.
     */
    @Test
    void aLinkSurvivesARestartAndKeepsRedirecting() {
        LinkResponse before = givenLink(alice());

        restartApplication();

        HttpResponse<String> click = api.click(before.code());
        LinkResponse after = ApiClient.asLink(api.getLink(alice(), before.code()));
        assertAll(
                () -> assertEquals(302, click.statusCode(), "a link that existed still redirects"),
                () -> assertEquals(
                        Fixtures.TARGET_URL,
                        ApiClient.header(click, Fixtures.LOCATION).orElse(null),
                        "to the same target it was created from"),
                () -> assertEquals(before.code(), after.code(), "under the same code"),
                () -> assertEquals(before.longUrl(), after.longUrl()),
                () -> assertEquals(before.expiresAt(), after.expiresAt()),
                () -> assertEquals(LinkStatus.ACTIVE, after.status()));
    }

    /**
     * Clicks counted before a restart are still counted after it: the total the
     * owner is shown is unchanged, so counting does not depend on anything that
     * dies with the process.
     *
     * <p>Demonstrates: AC3, AC7.
     */
    @Test
    void aClickCountSurvivesARestart() {
        LinkResponse link = givenLink(alice());
        clickRepeatedly(link.code(), 5);
        long beforeRestart = clickCount(alice(), link.code());

        restartApplication();

        long afterRestart = clickCount(alice(), link.code());
        assertAll(
                () -> assertEquals(5L, beforeRestart, "the five clicks were counted before the restart"),
                () -> assertEquals(5L, afterRestart,
                        "and are still counted after it - a count held only in the process is lost here"));
    }

    /**
     * Clicks made after a restart add to the retained total rather than starting
     * again from zero or from the durable figure alone.
     *
     * <p>Demonstrates: AC3, AC7.
     */
    @Test
    void clicksAfterARestartAddToTheRetainedTotal() {
        LinkResponse link = givenLink(alice());
        clickRepeatedly(link.code(), 3);

        restartApplication();

        clickRepeatedly(link.code(), 4);
        assertEquals(7L, clickCount(alice(), link.code()),
                "clicks after a restart add to the retained total rather than restarting it");
    }

    /**
     * A link deleted before a restart is still not redirecting afterwards: a
     * takedown is durable, not a fact held in a cache that a restart forgets.
     *
     * <p>Demonstrates: AC8, AC9.
     */
    @Test
    void aDeletedLinkStaysDownAcrossARestart() {
        String alice = alice();
        LinkResponse link = givenLink(alice);
        assertEquals(204, api.deleteLink(alice, link.code()).statusCode());

        restartApplication();

        HttpResponse<String> click = api.click(link.code());
        LinkResponse asItsOwnerSeesIt = ApiClient.asLink(api.getLink(alice(), link.code()));
        assertAll(
                () -> assertEquals(404, click.statusCode(), "a deleted link stays down across a restart"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, click.body()),
                () -> assertTrue(
                        ApiClient.header(click, Fixtures.LOCATION).isEmpty(),
                        "nothing may point at the target of a deleted link"),
                () -> assertEquals(LinkStatus.DELETED, asItsOwnerSeesIt.status(),
                        "the deletion is a stored fact, not a cached one"));
    }

    // ---- helpers ----------------------------------------------------------

    /** The click count currently reported for one of the caller's links. */
    private long clickCount(String bearer, String code) {
        HttpResponse<String> response = api.getLink(bearer, code);
        assertEquals(200, response.statusCode(), response.body());
        return ApiClient.asLink(response).clickCount();
    }
}
