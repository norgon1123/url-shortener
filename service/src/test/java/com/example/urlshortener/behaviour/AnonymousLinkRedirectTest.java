package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.AnonymousLinkResponse;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.link.ShortCodeGenerator;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An anonymously created code behaves like any other short link on the click
 * path (AC11).
 *
 * <p>Every behaviour here compares an anonymous code against an owned one
 * created in the same test, rather than against a remembered constant. AC11 is a
 * sameness claim - "the same redirect response", "the same mechanism" - and the
 * failure worth catching is a second, subtly different redirect path for
 * ownerless links, which a test written against fixed expectations would miss
 * whenever both paths drifted together.
 *
 * <p><strong>Counting is observed in storage, not through the API.</strong>
 * There is no endpoint that will ever report an anonymous link's click count -
 * that is AC13, and it is permanent - so the durable column is the only place
 * the number is visible. See {@code storedClickCount} on the harness for why
 * that is allowed here and nowhere else, and note that counting is asynchronous:
 * a click is drained into that column on the flush interval, so these behaviours
 * wait for a flush rather than reading immediately.
 */
class AnonymousLinkRedirectTest extends AbstractIntegrationTest {

    /**
     * Starts from full buckets and an empty shared tier.
     *
     * <p>This is a counting class, so the rule about never discarding a click
     * delta applies - and is respected: this runs before the first click of every
     * test here, so the only deltas it can discard belong to a class that has
     * already made its assertions. What it buys is that the anonymous-create
     * bucket, which another class empties on purpose, is full when these
     * behaviours need a link, and that the click and not-found buckets are not
     * inherited part-spent.
     */
    @BeforeEach
    void startFromFullBuckets() {
        resetSharedTierState();
    }

    /**
     * Following an anonymous code and following an owned code with the same target
     * produce the same response: the same status, the same {@code Location}, and
     * the same cache headers. 302 and never 301, because a 301 is cached
     * indefinitely by default and the later clicks would never reach us.
     *
     * <p>Demonstrates: AC11.
     */
    @Test
    void followingAnAnonymousCodeReturnsTheSameRedirectAsAnOwnedLink() {
        AnonymousLinkResponse anonymous = givenAnonymousLink(Fixtures.OTHER_TARGET_URL);
        LinkResponse owned = givenLink(alice(), Fixtures.OTHER_TARGET_URL);

        HttpResponse<String> anonymousClick = api.click(anonymous.code());
        HttpResponse<String> ownedClick = api.click(owned.code());

        assertAll(
                () -> assertEquals(302, anonymousClick.statusCode(), anonymousClick.body()),
                () -> assertNotEquals(301, anonymousClick.statusCode(),
                        "a 301 is cached indefinitely and the later clicks never reach us"),
                () -> assertEquals(ownedClick.statusCode(), anonymousClick.statusCode(),
                        "the same status as an owned link with the same target"),
                () -> assertEquals(Fixtures.OTHER_TARGET_URL,
                        ApiClient.header(anonymousClick, Fixtures.LOCATION).orElse(null)),
                () -> assertEquals(
                        ApiClient.header(ownedClick, Fixtures.LOCATION),
                        ApiClient.header(anonymousClick, Fixtures.LOCATION),
                        "and the same Location"),
                () -> assertEquals(Optional.of(Fixtures.NO_STORE),
                        ApiClient.header(anonymousClick, Fixtures.CACHE_CONTROL)),
                () -> assertEquals(
                        ApiClient.header(ownedClick, Fixtures.CACHE_CONTROL),
                        ApiClient.header(anonymousClick, Fixtures.CACHE_CONTROL)),
                () -> assertEquals(
                        ApiClient.header(ownedClick, Fixtures.PRAGMA),
                        ApiClient.header(anonymousClick, Fixtures.PRAGMA),
                        "including the header for intermediaries that predate Cache-Control"),
                () -> assertEquals(
                        ApiClient.header(ownedClick, Fixtures.EXPIRES),
                        ApiClient.header(anonymousClick, Fixtures.EXPIRES)));
    }

    /**
     * A {@code HEAD} on an anonymous code answers like the {@code GET}: the same
     * status and headers, no body. It is served by the same handler, so this is a
     * check that no separate anonymous route was introduced.
     *
     * <p>Demonstrates: AC11, AC17.
     */
    @Test
    void aHeadRequestOnAnAnonymousCodeAnswersLikeTheGetPath() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();

        HttpResponse<String> head = api.clickHead(anonymous.code());
        HttpResponse<String> get = api.click(anonymous.code());

        assertAll(
                () -> assertEquals(302, head.statusCode(), "HEAD is dispatched to the GET mapping"),
                () -> assertEquals(get.statusCode(), head.statusCode()),
                () -> assertEquals(
                        ApiClient.header(get, Fixtures.LOCATION),
                        ApiClient.header(head, Fixtures.LOCATION),
                        "with the same Location"),
                () -> assertEquals(
                        ApiClient.header(get, Fixtures.CACHE_CONTROL),
                        ApiClient.header(head, Fixtures.CACHE_CONTROL)),
                () -> assertTrue(head.body() == null || head.body().isEmpty(),
                        "and no body: " + head.body()));
    }

    /**
     * Presenting a credential when following an anonymous code changes nothing:
     * the click path is public and takes no credential either way.
     *
     * <p>Demonstrates: AC11.
     */
    @Test
    void aCredentialOnTheClickPathChangesNothingForAnAnonymousCode() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();

        HttpResponse<String> withNone = api.click(anonymous.code());
        HttpResponse<String> withAValidOne = api.clickWithBearer(anonymous.code(), alice());
        HttpResponse<String> withAForgedOne =
                api.clickWithBearer(anonymous.code(), Fixtures.FORGED_BEARER);

        assertAll(
                () -> assertEquals(302, withAValidOne.statusCode(), withAValidOne.body()),
                () -> assertEquals(302, withAForgedOne.statusCode(),
                        "the click path takes no credential, so a bad one is not a 401: "
                                + withAForgedOne.body()),
                () -> assertEquals(withNone.statusCode(), withAValidOne.statusCode()),
                () -> assertEquals(
                        ApiClient.header(withNone, Fixtures.LOCATION),
                        ApiClient.header(withAValidOne, Fixtures.LOCATION)),
                () -> assertEquals(
                        ApiClient.header(withNone, Fixtures.LOCATION),
                        ApiClient.header(withAForgedOne, Fixtures.LOCATION)));
    }

    /**
     * Clicks on an anonymous link are counted, and by the same mechanism: after a
     * known number of clicks and a flush, the durable count recorded against the
     * anonymous code equals the count recorded against an owned code clicked the
     * same number of times.
     *
     * <p>Demonstrates: AC11.
     */
    @Test
    void clicksOnAnAnonymousLinkAreCountedByTheSameMechanism() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        LinkResponse owned = givenLink(alice());

        clickRepeatedly(anonymous.code(), 4);
        clickRepeatedly(owned.code(), 4);
        awaitClickFlush();

        Optional<Long> anonymousCount = storedClickCount(anonymous.code());
        Optional<Long> ownedCount = storedClickCount(owned.code());
        assertAll(
                () -> assertEquals(Optional.of(4L), anonymousCount,
                        "four clicks on an anonymous link are four counted clicks"),
                () -> assertEquals(ownedCount, anonymousCount,
                        "counted by the same mechanism as an owned link clicked as often: owned "
                                + ownedCount + " against anonymous " + anonymousCount),
                () -> assertEquals(Optional.of(4L), ownedCount,
                        "and the comparison is not two matching zeroes"));
    }

    /**
     * Clicks that do not redirect are not counted for an anonymous link either: a
     * click after expiry, or on a code that was never issued, adds nothing.
     * Counting a 404 would let anyone inflate figures for a link they do not hold.
     *
     * <p>Demonstrates: AC11.
     */
    @Test
    void clicksThatDoNotRedirectAreNotCounted() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        clickRepeatedly(anonymous.code(), 2);
        awaitClickFlush();
        Optional<Long> afterTwoRealClicks = storedClickCount(anonymous.code());

        List<HttpResponse<String>> onAnUnissuedCode = clickRepeatedly(Fixtures.UNISSUED_CODE, 3);
        LinkResponse expired = givenExpiredLink(alice());
        List<HttpResponse<String>> afterExpiry = clickRepeatedly(expired.code(), 3);
        awaitClickFlush();

        assertAll(
                () -> assertEquals(Optional.of(2L), afterTwoRealClicks,
                        "the two redirects were counted"),
                () -> assertTrue(onAnUnissuedCode.stream().allMatch(r -> r.statusCode() == 404),
                        "the unissued code really did not redirect"),
                () -> assertTrue(afterExpiry.stream().allMatch(r -> r.statusCode() == 404),
                        "and neither did the expired one"),
                () -> assertEquals(afterTwoRealClicks, storedClickCount(anonymous.code()),
                        "nothing that answered 404 was added to the anonymous link's count"),
                () -> assertEquals(Optional.of(0L), storedClickCount(expired.code()),
                        "and a link clicked only after it expired counted nothing at all"));
    }

    /**
     * Concurrent clicks on an anonymous link are all counted - no loss under
     * simultaneous traffic, which is the failure that never shows up one click at
     * a time and is why the harness has a burst helper at all.
     *
     * <p>Demonstrates: AC11.
     */
    @Test
    void concurrentClicksOnAnAnonymousLinkAreAllCounted() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();

        List<HttpResponse<String>> burst = clickConcurrently(anonymous.code(), 20, 5);
        awaitClickFlush();

        assertAll(
                () -> assertTrue(burst.stream().allMatch(r -> r.statusCode() == 302),
                        "every click in the burst was served"),
                () -> assertEquals(Optional.of(20L), storedClickCount(anonymous.code()),
                        "and every one of them was counted: a counter that is right sequentially "
                                + "and lossy under load is the defect worth catching"));
    }

    /**
     * An anonymous code takes its place in the one namespace: it is the same
     * length and drawn from the same alphabet as a generated owned code, so
     * holding a pile of codes gives no way to tell which were minted with an
     * account and which were not.
     *
     * <p>Demonstrates: AC11, AC13.
     */
    @Test
    void anAnonymousCodeIsIndistinguishableInShapeFromAnOwnedOne() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        LinkResponse owned = givenLink(alice());

        assertAll(
                () -> assertEquals(owned.code().length(), anonymous.code().length(),
                        "same length: " + anonymous.code() + " against " + owned.code()),
                () -> assertEquals(ShortCodeGenerator.CODE_LENGTH, anonymous.code().length()),
                () -> assertTrue(
                        anonymous.code().chars().allMatch(c -> ShortCodeGenerator.ALPHABET.indexOf(c) >= 0),
                        "same alphabet, so codes cannot be sorted into owned and unowned by shape: "
                                + anonymous.code()),
                () -> assertNotEquals(owned.code(), anonymous.code(),
                        "and they are still separate codes in one namespace"));
    }
}
