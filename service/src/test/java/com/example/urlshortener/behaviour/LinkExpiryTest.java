package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.ApiError;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.domain.LinkStatus;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * When a link stops working on its own (AC10) and how its expiry is changed
 * (AC11).
 *
 * <p>Expiry is the whole of "editing a link": the target URL is immutable for the
 * life of the code, so a short link that has been shared cannot be repointed at
 * something else afterwards. The tests that need an expiry to pass use the
 * harness's short-expiry helper rather than reaching into the database, which is
 * not part of the frozen contract; three seconds is the cost, paid by four tests.
 */
class LinkExpiryTest extends AbstractIntegrationTest {

    /**
     * A link created without an expiry gets one about a month out - the configured
     * default rather than a number welded into the code.
     *
     * <p>Demonstrates: AC10, AC11.
     */
    @Test
    void aLinkCreatedWithoutAnExpiryGetsTheDefaultOne() {
        LinkResponse link = givenLink(alice());

        Duration granted = Duration.between(link.createdAt(), link.expiresAt());
        Duration drift = granted.minus(Fixtures.DEFAULT_LINK_TTL).abs();
        assertAll(
                () -> assertTrue(link.expiresAt().isAfter(link.createdAt())),
                () -> assertTrue(
                        drift.compareTo(Duration.ofMinutes(5)) < 0,
                        "about a month out, from app.links.default-ttl: got " + granted),
                () -> assertEquals(LinkStatus.ACTIVE, link.status()));
    }

    /**
     * Once its expiry has passed, a link no longer redirects: the click path
     * answers the single 404, and the owner sees it as expired.
     *
     * <p>Demonstrates: AC10.
     */
    @Test
    void anExpiredLinkNoLongerRedirects() {
        String alice = alice();
        LinkResponse link = givenLinkExpiringIn(alice, Fixtures.SHORT_EXPIRY);
        assertEquals(302, api.click(link.code()).statusCode(), "it redirects while it is live");

        // The harness own way of reaching an expired link: sit through the expiry,
        // then clear the resolution cache so that a cached row cannot be the reason
        // the next click answers either way.
        awaitExpiry(link);

        HttpResponse<String> click = api.click(link.code());
        LinkResponse asTheOwnerSeesIt = ApiClient.asLink(api.getLink(alice, link.code()));
        assertAll(
                () -> assertEquals(404, click.statusCode()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, click.body()),
                () -> assertEquals(
                        LinkStatus.EXPIRED, asTheOwnerSeesIt.status(), "and its owner sees why"));
    }

    /**
     * An individual link expiry can be set to a new future instant, the response
     * carries the new value, and a later fetch agrees - the change is per link and
     * needs no code change.
     *
     * <p>Demonstrates: AC11.
     */
    @Test
    void theExpiryOfAnIndividualLinkCanBeChanged() {
        String alice = alice();
        LinkResponse link = givenLink(alice);
        Instant newExpiry = Instant.now().plus(Duration.ofDays(90)).truncatedTo(ChronoUnit.SECONDS);

        HttpResponse<String> patched = api.updateExpiry(alice, link.code(), newExpiry);

        assertEquals(200, patched.statusCode(), patched.body());
        LinkResponse updated = ApiClient.asLink(patched);
        LinkResponse refetched = ApiClient.asLink(api.getLink(alice, link.code()));
        assertAll(
                () -> assertEquals(newExpiry, updated.expiresAt(), "the response carries the new value"),
                () -> assertEquals(newExpiry, refetched.expiresAt(), "and so does a later fetch"),
                () -> assertEquals(link.code(), updated.code()),
                () -> assertEquals(link.longUrl(), updated.longUrl(), "nothing else moved"),
                () -> assertEquals(LinkStatus.ACTIVE, updated.status()),
                () -> assertEquals(302, api.click(link.code()).statusCode()));
    }

    /**
     * Bringing the expiry forward stops the link redirecting once the new time
     * passes, without waiting out the old one and without waiting for a cache to
     * expire on its own.
     *
     * <p>Demonstrates: AC10, AC11, AC9.
     */
    @Test
    void bringingTheExpiryForwardStopsTheRedirectWhenItPasses() {
        String alice = alice();
        // Default expiry, a month out, and clicked once first so that whatever the
        // resolution cache does with a live link has already happened.
        LinkResponse link = givenLink(alice);
        assertEquals(302, api.click(link.code()).statusCode(), "live before the change");

        Instant broughtForward =
                Instant.now().plus(Fixtures.SHORT_EXPIRY).truncatedTo(ChronoUnit.SECONDS);
        HttpResponse<String> patched = api.updateExpiry(alice, link.code(), broughtForward);
        assertEquals(200, patched.statusCode(), patched.body());

        // Deliberately no cache eviction here: the contract says shortening the
        // expiry takes effect once the new time passes, and a test that cleared the
        // cache itself would pass against a service that never invalidated anything.
        awaitInstant(broughtForward.plusMillis(1500));

        HttpResponse<String> afterTheNewExpiry = api.click(link.code());
        LinkResponse asTheOwnerSeesIt = ApiClient.asLink(api.getLink(alice, link.code()));
        assertAll(
                () -> assertEquals(broughtForward, ApiClient.asLink(patched).expiresAt()),
                () -> assertTrue(
                        link.expiresAt().isAfter(broughtForward),
                        "the original expiry is still far away, so only the change explains a 404"),
                () -> assertEquals(404, afterTheNewExpiry.statusCode(), afterTheNewExpiry.body()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, afterTheNewExpiry.body()),
                () -> assertEquals(LinkStatus.EXPIRED, asTheOwnerSeesIt.status()));
    }

    /**
     * Pushing a soon-to-expire link expiry further out keeps it redirecting past
     * the original time.
     *
     * <p>Demonstrates: AC11, AC10.
     */
    @Test
    void pushingTheExpiryOutKeepsALinkRedirecting() {
        String alice = alice();
        LinkResponse link = givenLinkExpiringIn(alice, Fixtures.SHORT_EXPIRY);
        Instant originalExpiry = link.expiresAt();
        Instant pushedOut = Instant.now().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.SECONDS);

        HttpResponse<String> patched = api.updateExpiry(alice, link.code(), pushedOut);
        assertEquals(200, patched.statusCode(), patched.body());

        awaitInstant(originalExpiry.plusMillis(1500));

        HttpResponse<String> afterTheOriginalExpiry = api.click(link.code());
        LinkResponse asTheOwnerSeesIt = ApiClient.asLink(api.getLink(alice, link.code()));
        assertAll(
                () -> assertTrue(
                        Instant.now().isAfter(originalExpiry), "the original expiry really has passed"),
                () -> assertEquals(
                        302, afterTheOriginalExpiry.statusCode(), afterTheOriginalExpiry.body()),
                () -> assertEquals(
                        Fixtures.TARGET_URL,
                        ApiClient.header(afterTheOriginalExpiry, Fixtures.LOCATION).orElse(null)),
                () -> assertEquals(pushedOut, asTheOwnerSeesIt.expiresAt()),
                () -> assertEquals(LinkStatus.ACTIVE, asTheOwnerSeesIt.status()));
    }

    /**
     * An expiry in the past is 400 and changes nothing: the link keeps redirecting
     * and keeps its old expiry. A past timestamp is not a takedown - delete is,
     * and delete invalidates the caches, which a backdated expiry would not.
     *
     * <p>Demonstrates: AC11, AC9.
     */
    @Test
    void anExpiryInThePastIsRejectedAndChangesNothing() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        HttpResponse<String> backdated =
                api.updateExpiry(alice, link.code(), Instant.now().minus(Duration.ofDays(1)));

        LinkResponse afterwards = ApiClient.asLink(api.getLink(alice, link.code()));
        HttpResponse<String> click = api.click(link.code());
        assertAll(
                () -> assertEquals(400, backdated.statusCode(), backdated.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(backdated).error()),
                () -> assertTrue(namesField(backdated, "expiresAt"), backdated.body()),
                () -> assertEquals(link.expiresAt(), afterwards.expiresAt(), "the old expiry stands"),
                () -> assertEquals(LinkStatus.ACTIVE, afterwards.status()),
                () -> assertEquals(302, click.statusCode(), "a backdated expiry is not a takedown"));
    }

    /**
     * A patch with no expiry in it at all is 400 with the field named, rather than
     * a successful no-op.
     *
     * <p>Demonstrates: AC11.
     */
    @Test
    void aPatchWithoutAnExpiryIsRejected() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        HttpResponse<String> empty = api.updateLinkRaw(alice, link.code(), "{}");

        LinkResponse afterwards = ApiClient.asLink(api.getLink(alice, link.code()));
        assertAll(
                () -> assertEquals(400, empty.statusCode(), empty.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(empty).error()),
                () -> assertEquals("The request is not valid.", ApiClient.asError(empty).message()),
                () -> assertTrue(namesField(empty, "expiresAt"), empty.body()),
                () -> assertEquals(link.expiresAt(), afterwards.expiresAt(), "and nothing happened"));
    }

    /**
     * A patch carrying the target URL - or any other property - is 400, and the
     * link target is unchanged afterwards. Immutability is mechanical: the field
     * fails loudly instead of being silently ignored, which is what stops a shared
     * link being repointed at something else after the fact.
     *
     * <p>Demonstrates: AC11, AC21.
     */
    @Test
    void aPatchCarryingTheTargetUrlIsRejectedRatherThanIgnored() {
        String alice = alice();
        LinkResponse link = givenLink(alice);
        String newExpiry =
                Instant.now().plus(Duration.ofDays(5)).truncatedTo(ChronoUnit.SECONDS).toString();

        HttpResponse<String> repoint = api.updateLinkRaw(
                alice, link.code(), "{\"longUrl\":\"" + Fixtures.OTHER_TARGET_URL + "\"}");
        HttpResponse<String> smuggled = api.updateLinkRaw(
                alice,
                link.code(),
                "{\"expiresAt\":\"" + newExpiry + "\",\"longUrl\":\"" + Fixtures.OTHER_TARGET_URL
                        + "\"}");
        HttpResponse<String> statusChange = api.updateLinkRaw(
                alice, link.code(), "{\"expiresAt\":\"" + newExpiry + "\",\"status\":\"ACTIVE\"}");


        LinkResponse afterwards = ApiClient.asLink(api.getLink(alice, link.code()));
        HttpResponse<String> click = api.click(link.code());
        assertAll(
                () -> assertEquals(400, repoint.statusCode(), repoint.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(repoint).error()),
                () -> assertEquals(400, smuggled.statusCode(), smuggled.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(smuggled).error()),
                () -> assertEquals(400, statusChange.statusCode(), statusChange.body()),
                () -> assertEquals(Fixtures.TARGET_URL, afterwards.longUrl(), "the target never moves"),
                () -> assertEquals(
                        link.expiresAt(),
                        afterwards.expiresAt(),
                        "a refused patch changes nothing, not even the part that was valid"),
                () -> assertEquals(
                        Fixtures.TARGET_URL, ApiClient.header(click, Fixtures.LOCATION).orElse(null)));
    }

    /**
     * Patching another customer link is 404 - identical to patching a code that
     * was never issued - and that customer link is untouched: same expiry, still
     * redirecting.
     *
     * <p>Demonstrates: AC13, AC15.
     */
    @Test
    void patchingAnotherCustomersLinkIsNotFoundAndChangesNothing() {
        String alice = alice();
        String bob = bob();
        LinkResponse bobsLink = givenLink(bob);
        Instant attempted = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);

        HttpResponse<String> onBobsLink = api.updateExpiry(alice, bobsLink.code(), attempted);
        HttpResponse<String> onAnUnissuedCode =
                api.updateExpiry(alice, Fixtures.UNISSUED_CODE, attempted);

        LinkResponse bobsLinkAfterwards = ApiClient.asLink(api.getLink(bob, bobsLink.code()));
        HttpResponse<String> click = api.click(bobsLink.code());
        assertAll(
                () -> assertEquals(404, onBobsLink.statusCode(), onBobsLink.body()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, onBobsLink.body()),
                () -> assertEquals(
                        onAnUnissuedCode.statusCode(),
                        onBobsLink.statusCode(),
                        "another customer link answers exactly like a code never issued"),
                () -> assertEquals(onAnUnissuedCode.body(), onBobsLink.body()),
                () -> assertEquals(bobsLink.expiresAt(), bobsLinkAfterwards.expiresAt()),
                () -> assertEquals(LinkStatus.ACTIVE, bobsLinkAfterwards.status()),
                () -> assertEquals(302, click.statusCode(), "and it still works for its audience"));
    }

    /**
     * Patching a deleted link of the caller own is 409 {@code link_not_modifiable}: its
     * expiry no longer means anything, and the answer is a conflict rather than a
     * 404 because the caller does own it.
     *
     * <p>Demonstrates: AC8, AC11.
     */
    @Test
    void patchingOnesOwnDeletedLinkIsRefusedAsNotModifiable() {
        String alice = alice();
        LinkResponse deleted = givenDeletedLink(alice);

        HttpResponse<String> patched = api.updateExpiry(
                alice,
                deleted.code(),
                Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS));

        LinkResponse afterwards = ApiClient.asLink(api.getLink(alice, deleted.code()));
        assertAll(
                () -> assertEquals(409, patched.statusCode(), patched.body()),
                () -> assertEquals("link_not_modifiable", ApiClient.asError(patched).error()),
                () -> assertEquals(
                        "This link can no longer be modified.", ApiClient.asError(patched).message()),
                () -> assertEquals(LinkStatus.DELETED, afterwards.status(), "it stays deleted"),
                () -> assertEquals(404, api.click(deleted.code()).statusCode()));
    }

    /**
     * Patching a blocked link of the caller own is 409 and the link stays down: an
     * abuse takedown must not be reversible by its owner pushing the expiry out.
     *
     * <p>Demonstrates: AC21, AC9.
     */
    @Test
    void patchingOnesOwnBlockedLinkCannotUndoTheTakedown() {
        String alice = alice();
        String bob = bob();
        LinkResponse blocked = givenBlockedLink(alice, bob);

        HttpResponse<String> patched = api.updateExpiry(
                alice,
                blocked.code(),
                Instant.now().plus(Duration.ofDays(365)).truncatedTo(ChronoUnit.SECONDS));

        LinkResponse afterwards = ApiClient.asLink(api.getLink(alice, blocked.code()));
        HttpResponse<String> click = api.click(blocked.code());
        assertAll(
                () -> assertEquals(409, patched.statusCode(), patched.body()),
                () -> assertEquals("link_not_modifiable", ApiClient.asError(patched).error()),
                () -> assertEquals(LinkStatus.BLOCKED, afterwards.status(), "the takedown holds"),
                () -> assertEquals(
                        blocked.expiresAt(), afterwards.expiresAt(), "and the expiry did not move"),
                () -> assertEquals(404, click.statusCode()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, click.body()));
    }

    /**
     * Patching without a session is 401 {@code unauthorized}, whether or not the
     * code exists.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void patchingWithoutASessionIsRefused() {
        String alice = alice();
        LinkResponse link = givenLink(alice);
        Instant attempted = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);

        HttpResponse<String> onARealCode = api.updateExpiry(null, link.code(), attempted);
        HttpResponse<String> onAnUnissuedCode =
                api.updateExpiry(null, Fixtures.UNISSUED_CODE, attempted);

        LinkResponse afterwards = ApiClient.asLink(api.getLink(alice, link.code()));
        assertAll(
                () -> assertEquals(401, onARealCode.statusCode(), onARealCode.body()),
                () -> assertEquals("unauthorized", ApiClient.asError(onARealCode).error()),
                () -> assertEquals("Authentication required.", ApiClient.asError(onARealCode).message()),
                () -> assertEquals(401, onAnUnissuedCode.statusCode(), onAnUnissuedCode.body()),
                () -> assertEquals(
                        onARealCode.body(),
                        onAnUnissuedCode.body(),
                        "an unauthenticated caller cannot learn which codes exist"),
                () -> assertEquals(link.expiresAt(), afterwards.expiresAt(), "and nothing changed"));
    }

    // ---- helpers ----------------------------------------------------------

    /** True when the error body names {@code field} in its per-field detail. */
    private boolean namesField(HttpResponse<String> response, String field) {
        ApiError error = ApiClient.asError(response);
        return error.fields() != null && error.fields().containsKey(field);
    }
}
