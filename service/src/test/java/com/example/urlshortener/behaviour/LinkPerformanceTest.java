package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.domain.LinkStatus;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.URI;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * Fetching one link and how it has performed (AC7), and the fact that this
 * endpoint answers nothing at all about anybody else links (AC13, AC15).
 */
class LinkPerformanceTest extends AbstractIntegrationTest {

    /**
     * The owner of a link is shown the link and its click count: code, short URL,
     * target, status, creation and expiry times, and the exact number of clicks
     * served so far.
     *
     * <p>Demonstrates: AC7.
     */
    @Test
    void anOwnerSeesTheirLinkAndItsClickCount() {
        String alice = alice();
        LinkResponse created = givenLink(alice);
        clickRepeatedly(created.code(), 4);

        HttpResponse<String> response = api.getLink(alice, created.code());

        assertEquals(200, response.statusCode(), response.body());
        LinkResponse performance = ApiClient.asLink(response);
        assertAll(
                () -> assertEquals(created.code(), performance.code()),
                () -> assertEquals(created.shortUrl(), performance.shortUrl()),
                () -> assertEquals(
                        "/" + created.code(), URI.create(performance.shortUrl()).getPath()),
                () -> assertEquals(Fixtures.TARGET_URL, performance.longUrl()),
                () -> assertEquals(LinkStatus.ACTIVE, performance.status()),
                () -> assertEquals(created.createdAt(), performance.createdAt()),
                () -> assertEquals(created.expiresAt(), performance.expiresAt()),
                () -> assertNotNull(performance.createdAt()),
                () -> assertEquals(
                        4L, performance.clickCount(), "the count is the clicks served, exactly"));
    }

    /**
     * An owner still sees their own expired link, reported as expired, with the
     * count it accrued while it was live. It is their data and the row is kept;
     * only the redirect stops.
     *
     * <p>Demonstrates: AC7, AC10.
     */
    @Test
    void anOwnerSeesTheirOwnExpiredLinkWithItsRetainedCount() {
        String alice = alice();
        LinkResponse link = givenLinkExpiringIn(alice, Fixtures.SHORT_EXPIRY);
        clickRepeatedly(link.code(), 2);
        awaitExpiry(link);
        assertEquals(404, api.click(link.code()).statusCode(), "it has really stopped redirecting");

        HttpResponse<String> response = api.getLink(alice, link.code());

        assertEquals(200, response.statusCode(), "an expired link is still its owner data");
        LinkResponse expired = ApiClient.asLink(response);
        assertAll(
                () -> assertEquals(link.code(), expired.code()),
                () -> assertEquals(LinkStatus.EXPIRED, expired.status()),
                () -> assertEquals(Fixtures.TARGET_URL, expired.longUrl()),
                () -> assertEquals(2L, expired.clickCount(), "the count it accrued is retained"));
    }

    /**
     * An owner still sees their own deleted link, reported as deleted, with its
     * retained count.
     *
     * <p>Demonstrates: AC7, AC8.
     */
    @Test
    void anOwnerSeesTheirOwnDeletedLinkWithItsRetainedCount() {
        String alice = alice();
        LinkResponse link = givenLink(alice);
        clickRepeatedly(link.code(), 5);
        assertEquals(204, api.deleteLink(alice, link.code()).statusCode());

        HttpResponse<String> response = api.getLink(alice, link.code());

        assertEquals(200, response.statusCode(), "a soft delete keeps the row and its total");
        LinkResponse deleted = ApiClient.asLink(response);
        assertAll(
                () -> assertEquals(link.code(), deleted.code()),
                () -> assertEquals(LinkStatus.DELETED, deleted.status()),
                () -> assertEquals(5L, deleted.clickCount(), "the count survives the delete"),
                () -> assertEquals(404, api.click(link.code()).statusCode()));
    }

    /**
     * An owner still sees their own blocked link, reported as blocked, with its
     * retained count - so a takedown is visible to the person it affects rather
     * than making their link vanish silently.
     *
     * <p>Demonstrates: AC7, AC21.
     */
    @Test
    void anOwnerSeesTheirOwnBlockedLinkWithItsRetainedCount() {
        String alice = alice();
        String bob = bob();
        LinkResponse link = givenLink(alice);
        clickRepeatedly(link.code(), 3);
        assertEquals(202, api.reportAbuse(bob, link.code(), "Phishing").statusCode());

        HttpResponse<String> response = api.getLink(alice, link.code());

        assertEquals(200, response.statusCode(), "a takedown is visible, not silent");
        LinkResponse blocked = ApiClient.asLink(response);
        assertAll(
                () -> assertEquals(link.code(), blocked.code()),
                () -> assertEquals(LinkStatus.BLOCKED, blocked.status()),
                () -> assertEquals(3L, blocked.clickCount(), "the count is retained"),
                () -> assertEquals(404, api.click(link.code()).statusCode()));
    }

    /**
     * Another customer link is 404 - never 403 - and the link is untouched by the
     * attempt: its count does not move and it keeps redirecting.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void anotherCustomersLinkIsNotFound() {
        String alice = alice();
        String bob = bob();
        LinkResponse bobsLink = givenLink(bob);
        clickRepeatedly(bobsLink.code(), 2);

        HttpResponse<String> asSeenByAlice = api.getLink(alice, bobsLink.code());

        HttpResponse<String> click = api.click(bobsLink.code());
        assertAll(
                () -> assertEquals(404, asSeenByAlice.statusCode(), asSeenByAlice.body()),
                () -> assertNotEquals(403, asSeenByAlice.statusCode(), "403 would confirm it exists"),
                () -> assertEquals("not_found", ApiClient.asError(asSeenByAlice).error()),
                () -> assertEquals("Not found", ApiClient.asError(asSeenByAlice).message()),
                () -> assertTrue(
                        ApiClient.asError(asSeenByAlice).fields() == null,
                        "the not-found body carries no per-field detail"),
                () -> assertEquals(302, click.statusCode(), "and the owner link is untouched"),
                () -> assertEquals(3L, reportedClickCount(bob, bobsLink.code())));
    }

    /**
     * Asking about another customer live link and asking about a code that was
     * never issued produce the same status and the same body byte for byte, so
     * this endpoint cannot be used to discover which codes exist.
     *
     * <p>Demonstrates: AC13, AC15.
     */
    @Test
    void anotherCustomersLinkAndAnUnissuedCodeAnswerIdentically() {
        String alice = alice();
        String bob = bob();
        LinkResponse bobsLink = givenLink(bob);

        HttpResponse<String> somebodyElses = api.getLink(alice, bobsLink.code());
        HttpResponse<String> neverIssued = api.getLink(alice, Fixtures.UNISSUED_CODE);
        HttpResponse<String> couldNotExist = api.getLink(alice, Fixtures.MALFORMED_CODE);

        assertAll(
                () -> assertEquals(neverIssued.statusCode(), somebodyElses.statusCode()),
                () -> assertEquals(
                        neverIssued.body(),
                        somebodyElses.body(),
                        "the two answers must be identical byte for byte"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, somebodyElses.body()),
                () -> assertEquals(neverIssued.statusCode(), couldNotExist.statusCode()),
                () -> assertEquals(neverIssued.body(), couldNotExist.body()),
                () -> assertEquals(
                        ApiClient.header(neverIssued, "Content-Type").orElse(null),
                        ApiClient.header(somebodyElses, "Content-Type").orElse(null)));
    }

    /**
     * Asking about a link with no session is 401 {@code unauthorized} - the same
     * answer for a code that exists and one that does not, so an unauthenticated
     * caller learns nothing either.
     *
     * <p>Demonstrates: AC12, AC15.
     */
    @Test
    void fetchingALinkWithoutASessionIsRefused() {
        LinkResponse link = givenLink(alice());

        HttpResponse<String> onARealCode = api.getLink(null, link.code());
        HttpResponse<String> onAnUnissuedCode = api.getLink(null, Fixtures.UNISSUED_CODE);
        HttpResponse<String> withAForgedCredential = api.getLink(Fixtures.FORGED_BEARER, link.code());

        assertAll(
                () -> assertEquals(401, onARealCode.statusCode(), onARealCode.body()),
                () -> assertEquals("unauthorized", ApiClient.asError(onARealCode).error()),
                () -> assertEquals("Authentication required.", ApiClient.asError(onARealCode).message()),
                () -> assertEquals(401, onAnUnissuedCode.statusCode()),
                () -> assertEquals(
                        onARealCode.body(),
                        onAnUnissuedCode.body(),
                        "existing and non-existing codes answer identically without a session"),
                () -> assertEquals(401, withAForgedCredential.statusCode()),
                () -> assertEquals(onARealCode.body(), withAForgedCredential.body()));
    }
}
