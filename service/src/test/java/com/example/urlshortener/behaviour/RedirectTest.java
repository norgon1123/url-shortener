package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The click path: the public route at the root of the namespace.
 *
 * <p>This is the surface the product is, and the one with no credentials on it
 * ever (AC14). Everything here is observed on the response itself rather than
 * through a client that follows the redirect - the harness never follows one,
 * because the status, the {@code Location} and the cache headers are the
 * behaviour under test (AC2, AC4).
 *
 * <p>The single 404 is the other half: unknown, expired, deleted, blocked,
 * another customer's and malformed codes must all answer byte-identically, with
 * identical cache headers, or the difference is an existence oracle (AC15).
 */
class RedirectTest extends AbstractIntegrationTest {

    /**
     * Clicking a live short link answers 302 with {@code Location} exactly the URL
     * the link was created from, unmodified.
     *
     * <p>Demonstrates: AC2.
     */
    @Test
    void aLiveShortLinkRedirectsToItsTarget() {
        LinkResponse link = givenLink(alice(), Fixtures.TARGET_URL);

        HttpResponse<String> response = api.click(link.code());

        assertAll(
                () -> assertEquals(302, response.statusCode()),
                () -> assertEquals(Fixtures.TARGET_URL, location(response)));
    }

    /**
     * The click carries no credential of any kind and still redirects. Whoever
     * clicks is not our customer and will never sign in.
     *
     * <p>Demonstrates: AC14.
     */
    @Test
    void aClickNeedsNoCredentials() {
        LinkResponse link = givenLink(alice());

        // api.click sends no Authorization header, no cookie and no session.
        HttpResponse<String> response = api.click(link.code());

        assertAll(
                () -> assertEquals(302, response.statusCode()),
                () -> assertEquals(Fixtures.TARGET_URL, location(response)),
                () -> assertTrue(
                        ApiClient.header(response, Fixtures.WWW_AUTHENTICATE).isEmpty(),
                        "the click path must not challenge for credentials"));
    }

    /**
     * A click that happens to carry a session, or a nonsense credential, gets
     * exactly the same response as one with none: the click path does not
     * authenticate, so a bad credential cannot break a redirect.
     *
     * <p>Demonstrates: AC14.
     */
    @Test
    void presentingACredentialOnTheClickPathChangesNothing() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        HttpResponse<String> anonymous = api.click(link.code());
        HttpResponse<String> withSession = api.clickWithBearer(link.code(), alice);
        HttpResponse<String> withNonsense = api.clickWithBearer(link.code(), Fixtures.MALFORMED_BEARER);

        assertAll(
                () -> assertEquals(302, anonymous.statusCode()),
                () -> assertEquals(anonymous.statusCode(), withSession.statusCode()),
                () -> assertEquals(anonymous.statusCode(), withNonsense.statusCode()),
                () -> assertEquals(location(anonymous), location(withSession)),
                () -> assertEquals(location(anonymous), location(withNonsense)),
                () -> assertEquals(anonymous.body(), withSession.body()),
                () -> assertEquals(anonymous.body(), withNonsense.body()),
                () -> assertEquals(cacheControl(anonymous), cacheControl(withSession)),
                () -> assertEquals(cacheControl(anonymous), cacheControl(withNonsense)));
    }

    /**
     * A link created by one customer is clickable by anyone: ownership governs
     * management, never clicking.
     *
     * <p>Demonstrates: AC14, AC2.
     */
    @Test
    void anyoneMayClickAnyLiveLink() {
        LinkResponse alicesLink = givenLink(alice(), Fixtures.OTHER_TARGET_URL);
        String bob = bob();

        HttpResponse<String> byAStranger = api.click(alicesLink.code());
        HttpResponse<String> byAnotherCustomer = api.clickWithBearer(alicesLink.code(), bob);

        assertAll(
                () -> assertEquals(302, byAStranger.statusCode()),
                () -> assertEquals(Fixtures.OTHER_TARGET_URL, location(byAStranger)),
                () -> assertEquals(302, byAnotherCustomer.statusCode()),
                () -> assertEquals(Fixtures.OTHER_TARGET_URL, location(byAnotherCustomer)));
    }

    /**
     * The redirect forbids storage and reuse by browsers and intermediaries:
     * {@code Cache-Control: no-store, no-cache, must-revalidate, max-age=0}, plus
     * {@code Pragma: no-cache} and {@code Expires: 0}. Without these the second
     * click never reaches us and the count silently stops growing.
     *
     * <p>Demonstrates: AC4, AC3.
     */
    @Test
    void theRedirectForbidsCachingByBrowsersAndIntermediaries() {
        LinkResponse link = givenLink(alice());

        HttpResponse<String> response = api.click(link.code());

        assertAll(
                () -> assertEquals(302, response.statusCode()),
                () -> assertEquals(Fixtures.NO_STORE, cacheControl(response)),
                () -> assertEquals("no-cache", ApiClient.header(response, Fixtures.PRAGMA).orElse(null)),
                () -> assertEquals("0", ApiClient.header(response, Fixtures.EXPIRES).orElse(null)));
    }

    /**
     * The status is a temporary redirect and never a permanent one. A 301 is
     * cached indefinitely by default, so later clicks would never arrive - which
     * would break the exact count, the delete taking effect and the takedown bound
     * at once, none of it visible in a test of this service.
     *
     * <p>Demonstrates: AC4, AC8, AC9.
     */
    @Test
    void theRedirectIsTemporaryAndNeverPermanent() {
        LinkResponse link = givenLink(alice());

        HttpResponse<String> response = api.click(link.code());

        assertAll(
                () -> assertEquals(302, response.statusCode()),
                () -> assertNotEquals(301, response.statusCode(), "301 is cached indefinitely by default"),
                () -> assertNotEquals(308, response.statusCode(), "308 is a permanent redirect too"));
    }

    /**
     * The second and every subsequent click on the same link reaches the service
     * and gets its own 302 with the same target: nothing about the first response
     * lets a client skip us.
     *
     * <p>Demonstrates: AC4, AC3.
     */
    @Test
    void everyRepeatedClickReachesTheServiceAndRedirects() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        List<HttpResponse<String>> clicks = clickRepeatedly(link.code(), 5);

        assertAll(
                () -> assertTrue(
                        clicks.stream().allMatch(r -> r.statusCode() == 302),
                        "every repeated click must get its own redirect"),
                () -> assertTrue(
                        clicks.stream().allMatch(r -> Fixtures.TARGET_URL.equals(location(r))),
                        "every repeated click must be sent to the same target"),
                // The count is the evidence that all five arrived here rather than
                // being served from something that remembered the first one.
                () -> assertEquals(5L, reportedClickCount(alice, link.code())));
    }

    /**
     * HEAD on a short code answers with the same status and the same headers as
     * GET and no body - it is the same handler, and link checkers use it.
     *
     * <p>Demonstrates: AC2, AC4.
     */
    @Test
    void headAnswersLikeGetWithNoBody() {
        LinkResponse link = givenLink(alice());

        HttpResponse<String> get = api.click(link.code());
        HttpResponse<String> head = api.clickHead(link.code());

        assertAll(
                () -> assertEquals(get.statusCode(), head.statusCode()),
                () -> assertEquals(302, head.statusCode()),
                () -> assertEquals(location(get), location(head)),
                () -> assertEquals(cacheControl(get), cacheControl(head)),
                () -> assertEquals(
                        ApiClient.header(get, Fixtures.PRAGMA), ApiClient.header(head, Fixtures.PRAGMA)),
                () -> assertEquals(
                        ApiClient.header(get, Fixtures.EXPIRES), ApiClient.header(head, Fixtures.EXPIRES)),
                () -> assertEquals("", head.body(), "HEAD carries no body"));
    }

    /**
     * A code that was never issued does not redirect: 404, with the single
     * not-found body, and no {@code Location}.
     *
     * <p>Demonstrates: AC15.
     */
    @Test
    void aCodeThatWasNeverIssuedDoesNotRedirect() {
        HttpResponse<String> response = api.click(Fixtures.UNISSUED_CODE);

        assertAll(
                () -> assertEquals(404, response.statusCode()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, response.body()),
                () -> assertTrue(
                        ApiClient.header(response, Fixtures.LOCATION).isEmpty(),
                        "a code that does not resolve must not carry a Location"));
    }

    /**
     * A code that could not possibly have been issued - wrong charset, wrong
     * length - answers exactly like one that simply does not exist, never 400.
     * A different answer would tell an enumerator which shapes are worth trying.
     *
     * <p>Demonstrates: AC15, AC16.
     */
    @Test
    void aMalformedCodeAnswersExactlyLikeAnUnissuedOne() {
        HttpResponse<String> malformed = api.click(Fixtures.MALFORMED_CODE);
        HttpResponse<String> unissued = api.click(Fixtures.UNISSUED_CODE);

        assertAll(
                () -> assertEquals(404, malformed.statusCode()),
                () -> assertNotEquals(
                        400, malformed.statusCode(), "a 400 would tell an enumerator which shapes are worth trying"),
                () -> assertEquals(unissued.statusCode(), malformed.statusCode()),
                () -> assertEquals(unissued.body(), malformed.body()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, malformed.body()),
                () -> assertEquals(cacheControl(unissued), cacheControl(malformed)));
    }

    /**
     * Expired, deleted, blocked, another customer's and never-issued codes all
     * produce the same status, the same body byte for byte, and the same headers.
     * Response shape, size and cacheability leak nothing about which case it was.
     *
     * <p>Demonstrates: AC15, AC13, AC8, AC10, AC21.
     */
    @Test
    void everyUnusableCodeAnswersIndistinguishably() {
        String alice = alice();
        String bob = bob();
        // "Another customer's" has to mean an unusable link of another customer's
        // here: a live link of theirs is public and redirects for anyone (AC14),
        // so the ownership of a code may only show through when it does not
        // resolve at all.
        List<HttpResponse<String>> answers = List.of(
                api.click(givenExpiredLink(alice).code()),
                api.click(givenDeletedLink(alice).code()),
                api.click(givenBlockedLink(alice, bob).code()),
                api.click(givenDeletedLink(bob).code()),
                api.click(Fixtures.UNISSUED_CODE));

        HttpResponse<String> first = answers.get(0);
        assertAll(
                () -> assertTrue(
                        answers.stream().allMatch(r -> r.statusCode() == 404),
                        "every unusable code answers 404 - never 403, never 410"),
                () -> assertTrue(
                        answers.stream().allMatch(r -> Fixtures.NOT_FOUND_BODY.equals(r.body())),
                        "every unusable code answers the single not-found body, byte for byte"),
                () -> assertTrue(
                        answers.stream().allMatch(r -> cacheControl(first).equals(cacheControl(r))),
                        "the cause must not show through the cache headers either"),
                () -> assertTrue(
                        answers.stream().allMatch(r -> ApiClient.header(r, Fixtures.LOCATION).isEmpty()),
                        "none of them may carry a Location"));
    }

    /**
     * A 404 on the click path carries the same no-store cache headers as a
     * redirect, so an intermediary cannot cache "no such link" either - and the
     * two responses cannot be told apart by their cacheability.
     *
     * <p>Demonstrates: AC15, AC4.
     */
    @Test
    void aNotFoundCarriesTheSameCacheHeadersAsARedirect() {
        LinkResponse link = givenLink(alice());

        HttpResponse<String> redirect = api.click(link.code());
        HttpResponse<String> notFound = api.click(Fixtures.UNISSUED_CODE);

        assertAll(
                () -> assertEquals(302, redirect.statusCode()),
                () -> assertEquals(404, notFound.statusCode()),
                () -> assertEquals(Fixtures.NO_STORE, cacheControl(notFound)),
                () -> assertEquals(cacheControl(redirect), cacheControl(notFound)),
                () -> assertEquals(
                        ApiClient.header(redirect, Fixtures.PRAGMA),
                        ApiClient.header(notFound, Fixtures.PRAGMA)),
                () -> assertEquals(
                        ApiClient.header(redirect, Fixtures.EXPIRES),
                        ApiClient.header(notFound, Fixtures.EXPIRES)));
    }

    /**
     * A method other than GET or HEAD on a short code is refused by the
     * dispatcher, identically whether or not the code resolves - so the refusal is
     * not a way to ask whether a link exists.
     *
     * <p>What is pinned here is what the contract promises: 405 either way, no
     * {@code Location} either way, and nothing in the refusal that tells the
     * caller which of the two codes resolves. It is deliberately <em>not</em>
     * byte-equality of the two bodies: the refusal is Spring's own, and its body
     * legitimately echoes the request path the caller just sent and the clock at
     * the moment it was refused. Neither discloses anything the caller did not
     * already have. Everything else in the refusal must match.
     *
     * <p>Demonstrates: AC15.
     */
    @Test
    void aMethodOtherThanGetOrHeadIsRefusedWithoutRevealingWhetherTheCodeExists() {
        LinkResponse link = givenLink(alice(), Fixtures.TARGET_URL);

        HttpResponse<String> postOnALiveCode = api.rootRequest("POST", "/" + link.code(), "{}");
        HttpResponse<String> postOnAnUnissuedCode = api.rootRequest("POST", "/" + Fixtures.UNISSUED_CODE, "{}");
        HttpResponse<String> deleteOnALiveCode = api.rootRequest("DELETE", "/" + link.code(), null);
        HttpResponse<String> deleteOnAnUnissuedCode =
                api.rootRequest("DELETE", "/" + Fixtures.UNISSUED_CODE, null);

        assertAll(
                () -> assertEquals(405, postOnALiveCode.statusCode()),
                () -> assertEquals(postOnALiveCode.statusCode(), postOnAnUnissuedCode.statusCode()),
                () -> assertEquals(
                        refusalDisclosure(postOnALiveCode),
                        refusalDisclosure(postOnAnUnissuedCode),
                        "a refused POST must say the same thing for a live code as for an unissued one"),
                () -> assertEquals(405, deleteOnALiveCode.statusCode()),
                () -> assertEquals(deleteOnALiveCode.statusCode(), deleteOnAnUnissuedCode.statusCode()),
                () -> assertEquals(
                        refusalDisclosure(deleteOnALiveCode),
                        refusalDisclosure(deleteOnAnUnissuedCode),
                        "a refused DELETE must say the same thing for a live code as for an unissued one"),
                () -> assertFalse(
                        ApiClient.header(postOnALiveCode, Fixtures.LOCATION).isPresent(),
                        "a refused method must not redirect anyone"),
                () -> assertFalse(
                        ApiClient.header(deleteOnALiveCode, Fixtures.LOCATION).isPresent(),
                        "a refused method must not redirect anyone"),
                () -> assertFalse(
                        postOnALiveCode.body().contains(Fixtures.TARGET_URL),
                        "a refusal must not leak the target of the link it was aimed at"),
                () -> assertFalse(
                        deleteOnALiveCode.body().contains(Fixtures.TARGET_URL),
                        "a refusal must not leak the target of the link it was aimed at"));
    }

    // ---- helpers ----------------------------------------------------------

    /** The {@code Location} of a response, or null when it carries none. */
    private String location(HttpResponse<String> response) {
        return ApiClient.header(response, Fixtures.LOCATION).orElse(null);
    }

    /** The {@code Cache-Control} of a response, or empty when it carries none. */
    private String cacheControl(HttpResponse<String> response) {
        return ApiClient.header(response, Fixtures.CACHE_CONTROL).orElse("");
    }

    /**
     * Fields of an error body that vary for reasons that have nothing to do with
     * whether the code resolves: the request path the caller itself sent, and the
     * instant the refusal was produced.
     */
    private static final Set<String> CALLER_KNOWN_ERROR_FIELDS = Set.of("path", "timestamp", "instance");

    /**
     * Everything a refusal body says other than what the caller already knows -
     * the shape a test can hold two refusals to without demanding that an echoed
     * request path and a clock reading be identical. An empty body yields an
     * empty view, which two empty bodies still compare equal on.
     */
    private Map<String, String> refusalDisclosure(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        JsonNode tree = ApiClient.asTree(response);
        Map<String, String> disclosed = new TreeMap<>();
        tree.fieldNames().forEachRemaining(name -> {
            if (!CALLER_KNOWN_ERROR_FIELDS.contains(name)) {
                disclosed.put(name, tree.get(name).asText());
            }
        });
        return disclosed;
    }
}
