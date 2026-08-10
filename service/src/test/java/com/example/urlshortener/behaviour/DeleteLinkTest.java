package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Taking a link down (AC8), inside the bound the business is held to (AC9).
 *
 * <p>The bound is sixty seconds measured from the delete response, and it is
 * published in the API document, so it is quoted from the harness rather than
 * guessed at here. It is measured rather than assumed: the harness reports how
 * long the link kept redirecting after the response, and the test says what that
 * number has to be under.
 */
class DeleteLinkTest extends AbstractIntegrationTest {

    /**
     * After its owner deletes it, clicking the link no longer redirects to the
     * original address.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aDeletedLinkNoLongerRedirects() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        HttpResponse<String> deleted = api.deleteLink(alice, link.code());

        HttpResponse<String> click = api.click(link.code());
        assertAll(
                () -> assertEquals(204, deleted.statusCode(), deleted.body()),
                () -> assertEquals(404, click.statusCode()),
                () -> assertTrue(
                        ApiClient.header(click, Fixtures.LOCATION).isEmpty(),
                        "nothing may still point at the original address"));
    }

    /**
     * The link stops redirecting within the published bound, measured from the
     * delete response - not merely eventually, and not only after a cache has aged
     * out on its own.
     *
     * <p>Demonstrates: AC9, AC8.
     */
    @Test
    void aDeleteTakesEffectWithinThePublishedBound() {
        String alice = alice();
        LinkResponse link = givenLink(alice);
        clickRepeatedly(link.code(), 2);

        assertEquals(204, api.deleteLink(alice, link.code()).statusCode());
        // Measured from the delete response, without clearing any cache: the
        // implementation has to invalidate, not wait for a TTL.
        Optional<Duration> tookEffectAfter =
                observeUntil(() -> api.click(link.code()).statusCode() == 404, Fixtures.TAKEDOWN_BOUND);

        assertAll(
                () -> assertTrue(
                        tookEffectAfter.isPresent(),
                        "the link was still redirecting after the published bound of "
                                + Fixtures.TAKEDOWN_BOUND),
                () -> assertTrue(
                        tookEffectAfter.orElseThrow().compareTo(Fixtures.TAKEDOWN_BOUND) < 0,
                        "it stopped after " + tookEffectAfter.orElse(null)));
    }

    /**
     * A link that was clicked before the delete stops redirecting too: a
     * previously resolved, cached link is invalidated rather than left to serve
     * from the cache.
     *
     * <p>Demonstrates: AC8, AC9.
     */
    @Test
    void aLinkThatWasAlreadyBeingClickedStopsToo() {
        String alice = alice();
        LinkResponse link = givenLink(alice);
        // Resolve it first, so that whatever cache exists is holding this link.
        assertEquals(302, api.click(link.code()).statusCode());
        assertEquals(302, api.click(link.code()).statusCode());

        assertEquals(204, api.deleteLink(alice, link.code()).statusCode());

        // No evictResolutionCache() here on purpose: the contract requires the
        // delete to invalidate, and clearing the cache ourselves would pass
        // against an implementation that never does.
        HttpResponse<String> afterDelete = api.click(link.code());
        assertAll(
                () -> assertEquals(404, afterDelete.statusCode(),
                        "a link that was already resolved and cached stops too"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, afterDelete.body()));
    }

    /**
     * Deleting the same link again is another 204: the operation is idempotent, so
     * a retried delete is not an error and does not disclose that the first one
     * worked.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void deletingAnAlreadyDeletedLinkIsAcceptedAgain() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        HttpResponse<String> first = api.deleteLink(alice, link.code());
        HttpResponse<String> second = api.deleteLink(alice, link.code());
        HttpResponse<String> third = api.deleteLink(alice, link.code());

        assertAll(
                () -> assertEquals(204, first.statusCode()),
                () -> assertEquals(204, second.statusCode(), "a retried delete is not an error"),
                () -> assertEquals(204, third.statusCode()),
                () -> assertEquals(first.body(), second.body()));
    }

    /**
     * The delete response carries no body at all. There is nothing to say, and a
     * body describing what was deleted would be a way to confirm what existed.
     *
     * <p>Demonstrates: AC8, AC13.
     */
    @Test
    void aDeleteReturnsNoBody() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        HttpResponse<String> deleted = api.deleteLink(alice, link.code());

        assertAll(
                () -> assertEquals(204, deleted.statusCode()),
                () -> assertEquals("", deleted.body(),
                        "a body describing what was deleted confirms what existed"),
                () -> assertTrue(
                        deleted.headers().firstValueAsLong("Content-Length").orElse(0L) == 0L,
                        "nothing was sent back at all"));
    }

    /**
     * On the click path a deleted code answers exactly as a code that was never
     * issued: same status, same body, same headers, and it is not counted.
     *
     * <p>Demonstrates: AC8, AC15, AC3.
     */
    @Test
    void aDeletedCodeAnswersExactlyLikeAnUnissuedOne() {
        String alice = alice();
        LinkResponse link = givenLink(alice);
        clickRepeatedly(link.code(), 2);
        assertEquals(204, api.deleteLink(alice, link.code()).statusCode());

        HttpResponse<String> deletedCode = api.click(link.code());
        HttpResponse<String> unissuedCode = api.click(Fixtures.UNISSUED_CODE);

        assertAll(
                () -> assertEquals(unissuedCode.statusCode(), deletedCode.statusCode()),
                () -> assertEquals(404, deletedCode.statusCode()),
                () -> assertEquals(unissuedCode.body(), deletedCode.body()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, deletedCode.body()),
                () -> assertEquals(
                        ApiClient.header(unissuedCode, Fixtures.CACHE_CONTROL),
                        ApiClient.header(deletedCode, Fixtures.CACHE_CONTROL)),
                () -> assertEquals(2L, reportedClickCount(alice, link.code()),
                        "and the request that did not resolve was not counted"));
    }

    /**
     * Deleting another customer's link is 404 - identical to deleting a code that
     * was never issued - and their link keeps redirecting afterwards. This is the
     * interference half of tenant isolation: not only can it not be seen, it
     * cannot be touched.
     *
     * <p>Demonstrates: AC13, AC15.
     */
    @Test
    void deletingAnotherCustomersLinkIsNotFoundAndLeavesItWorking() {
        String alice = alice();
        String bob = bob();
        LinkResponse alicesLink = givenLink(alice);

        HttpResponse<String> bobDeletingIt = api.deleteLink(bob, alicesLink.code());
        HttpResponse<String> bobDeletingAnUnissuedCode = api.deleteLink(bob, Fixtures.UNISSUED_CODE);

        HttpResponse<String> click = api.click(alicesLink.code());
        assertAll(
                () -> assertEquals(404, bobDeletingIt.statusCode(), bobDeletingIt.body()),
                () -> assertEquals(bobDeletingAnUnissuedCode.statusCode(), bobDeletingIt.statusCode()),
                () -> assertEquals(bobDeletingAnUnissuedCode.body(), bobDeletingIt.body(),
                        "identical to a code that was never issued"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, bobDeletingIt.body()),
                () -> assertEquals(302, click.statusCode(), "and their link keeps redirecting"),
                () -> assertEquals(
                        Fixtures.TARGET_URL, ApiClient.header(click, Fixtures.LOCATION).orElse(null)),
                () -> assertEquals(200, api.getLink(alice, alicesLink.code()).statusCode()));
    }

    /**
     * Deleting without a session is 401 {@code unauthorized} and the link is
     * untouched.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void deletingWithoutASessionIsRefused() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        HttpResponse<String> withNoSession = api.deleteLink(null, link.code());

        HttpResponse<String> click = api.click(link.code());
        assertAll(
                () -> assertEquals(401, withNoSession.statusCode(), withNoSession.body()),
                () -> assertEquals("unauthorized", ApiClient.asError(withNoSession).error()),
                () -> assertEquals(302, click.statusCode(), "the link is untouched"),
                () -> assertEquals(200, api.getLink(alice, link.code()).statusCode()));
    }
}
