package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.AnonymousLinkResponse;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.link.ShortCodeGenerator;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Turning a long URL into a short link with no account (AC9), and everything
 * that is refused before one exists (AC12).
 *
 * <p>The response is the only copy of the link's details anyone will ever hold:
 * there is no endpoint that reads an anonymous link back, by design and
 * permanently. That makes the shape of this one body worth pinning field by
 * field, including the two fields it must not have.
 */
class AnonymousLinkCreationTest extends AbstractIntegrationTest {

    /**
     * Starts from a full anonymous-create bucket.
     *
     * <p>That bucket is keyed by client address, every test in this suite comes
     * from loopback, and one Redis is shared by every context in this JVM - so a
     * class that emptied it deliberately (there is one) leaves it empty for
     * whatever runs next. Several behaviours here post twenty-odd targets in a
     * row; without this they would be answered 429 and every failure would read as
     * a defect in creation. Throttling is {@code AnonymousCreateRateLimitTest}'s
     * subject, and this runs before the first click of each test here.
     */
    @BeforeEach
    void startFromFullBuckets() {
        resetSharedTierState();
    }

    /**
     * A caller with no credentials posts a long URL and gets 201 with the code and
     * the short URL to hand out, the target unchanged, and the two instants. The
     * short URL is the configured origin plus the code, in the same form the
     * authenticated create returns.
     *
     * <p>Demonstrates: AC9.
     */
    @Test
    void anUnauthenticatedCallerReceivesAShortLinkForTheirLongUrl() {
        Instant beforeTheRequest = Instant.now();

        HttpResponse<String> created = api.createAnonymousLink(Fixtures.TARGET_URL);

        assertEquals(201, created.statusCode(), created.body());
        AnonymousLinkResponse link = ApiClient.asAnonymousLink(created);
        LinkResponse owned = givenLink(alice(), Fixtures.TARGET_URL);
        assertAll(
                () -> assertNotNull(link.code(), created.body()),
                () -> assertEquals(ShortCodeGenerator.CODE_LENGTH, link.code().length(),
                        "the same code namespace as every other link: " + link.code()),
                () -> assertEquals(Fixtures.TARGET_URL, link.longUrl(),
                        "the target comes back exactly as submitted"),
                () -> assertTrue(URI.create(link.shortUrl()).isAbsolute(),
                        "the short URL is something a holder can paste: " + link.shortUrl()),
                () -> assertEquals("/" + link.code(), URI.create(link.shortUrl()).getPath(),
                        "which is the origin plus the code: " + link.shortUrl()),
                () -> assertEquals(originOf(owned.shortUrl()), originOf(link.shortUrl()),
                        "in the same form the authenticated create returns"),
                () -> assertNotNull(link.createdAt(), created.body()),
                () -> assertFalse(link.createdAt().isBefore(beforeTheRequest.minusSeconds(60)),
                        "createdAt is when it was created: " + link.createdAt()),
                () -> assertTrue(link.expiresAt().isAfter(link.createdAt()),
                        "and it expires after it was made: " + link.expiresAt()),
                () -> assertEquals(302, api.click(link.code()).statusCode(),
                        "and the code works immediately"));
    }

    /**
     * The response carries no {@code status} and no {@code clickCount}, and no
     * {@code Location} header. The two fields are absent because nobody may ever
     * read an anonymous link's numbers, and a body that carried them would invite
     * a client to expect to fetch them again from an endpoint that will only ever
     * answer 404; the header is absent because it could only point at that
     * endpoint.
     *
     * <p>Demonstrates: AC9, AC13.
     */
    @Test
    void theResponseOmitsStatusClickCountAndLocation() {
        HttpResponse<String> created = api.createAnonymousLink(Fixtures.TARGET_URL);

        assertEquals(201, created.statusCode(), created.body());
        JsonNode body = ApiClient.asTree(created);
        assertAll(
                () -> assertFalse(body.has("status"),
                        "no status: nobody may ever read one back: " + created.body()),
                () -> assertFalse(body.has("clickCount"),
                        "no clickCount, for the same reason: " + created.body()),
                () -> assertFalse(body.has("customerId"), created.body()),
                () -> assertTrue(ApiClient.header(created, Fixtures.LOCATION).isEmpty(),
                        "a Location could only point at an endpoint that answers 404 for this code"),
                () -> assertEquals(
                        Set.of("code", "shortUrl", "longUrl", "createdAt", "expiresAt"),
                        propertiesOf(body),
                        "the only copy of this link's details anybody will hold: " + created.body()));
    }

    /**
     * The link is stored with no owner at all. This is the structural half of
     * AC13: a service that homed anonymous links on a hidden placeholder account
     * would answer 404 to every request in this suite and still be wrong, because
     * that account's list would contain them and whoever held its credentials
     * could delete them.
     *
     * <p>Demonstrates: AC9, AC13.
     */
    @Test
    void theCreatedLinkIsStoredWithNoOwner() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        LinkResponse owned = givenLink(alice());

        assertAll(
                () -> assertEquals(Optional.of(true), storedLinkIsUnowned(anonymous.code()),
                        "an anonymous link has no owner, not a placeholder one"),
                () -> assertEquals(Optional.of(false), storedLinkIsUnowned(owned.code()),
                        "while an owned link does - so the check really distinguishes them"));
    }

    /**
     * The expiry is one month after creation - the configured anonymous TTL,
     * applied at creation as an absolute instant - and it is reported in the
     * response so the holder knows when their link stops working.
     *
     * <p>Demonstrates: AC10.
     */
    @Test
    void anAnonymousLinkExpiresOneMonthAfterItIsCreated() {
        AnonymousLinkResponse link = givenAnonymousLink();

        Duration lifetime = Duration.between(link.createdAt(), link.expiresAt());
        assertAll(
                () -> assertTrue(
                        lifetime.minus(Fixtures.ANONYMOUS_LINK_TTL).abs().compareTo(Duration.ofSeconds(5))
                                < 0,
                        "one month on the default configuration, measured from creation, but this link "
                                + "lives for " + lifetime),
                () -> assertTrue(link.expiresAt().isAfter(Instant.now()),
                        "and it is an absolute instant in the future: " + link.expiresAt()));
    }

    /**
     * An {@code expiresAt} property is refused with 400 rather than honoured or
     * ignored. The expiry of an anonymous link is the service's to set: nobody
     * owns the link, so nobody can shorten it afterwards, and letting the creator
     * choose it once would be the only lever anyone ever had on a row no one can
     * delete.
     *
     * <p>Demonstrates: AC9, AC10.
     */
    @Test
    void anExpiresAtPropertyIsRefused() {
        String chosen = Instant.now().plus(Duration.ofDays(3650)).toString();

        HttpResponse<String> refused = api.createAnonymousLinkRaw(
                "{\"longUrl\":\"" + Fixtures.TARGET_URL + "\",\"expiresAt\":\"" + chosen + "\"}");

        assertAll(
                () -> assertEquals(400, refused.statusCode(),
                        "an undeclared property is refused, not honoured and not dropped: "
                                + refused.body()),
                () -> assertEquals(Fixtures.INVALID_REQUEST, ApiClient.asError(refused).error()),
                () -> assertFalse(ApiClient.asTree(refused).has("code"),
                        "and nothing was minted: " + refused.body()));
    }

    /**
     * An {@code alias} property is refused with 400. Aliases share one namespace
     * with generated codes and a code is never reissued, so an unidentified caller
     * choosing memorable ones is permanent namespace squatting with no owner to
     * revoke it.
     *
     * <p>Demonstrates: AC9.
     */
    @Test
    void anAliasPropertyIsRefused() {
        String alias = Fixtures.uniqueAlias(Fixtures.ALIAS);

        HttpResponse<String> refused = api.createAnonymousLinkRaw(
                "{\"longUrl\":\"" + Fixtures.TARGET_URL + "\",\"alias\":\"" + alias + "\"}");

        assertAll(
                () -> assertEquals(400, refused.statusCode(),
                        "an anonymous caller does not get to choose a permanent code: " + refused.body()),
                () -> assertEquals(Fixtures.INVALID_REQUEST, ApiClient.asError(refused).error()),
                () -> assertEquals(404, api.click(alias).statusCode(),
                        "and the alias was not quietly taken anyway"));
    }

    /**
     * A body with no target, a blank target, or a target that is not an absolute
     * http(s) URL is refused with 400 {@code invalid_request}, exactly as the
     * authenticated path refuses the same bodies.
     *
     * <p>Demonstrates: AC9.
     */
    @Test
    void aBodyWithoutAUsableTargetIsRefused() {
        HttpResponse<String> absent = api.createAnonymousLink(null);
        HttpResponse<String> blank = api.createAnonymousLink("");
        HttpResponse<String> notAUrl = api.createAnonymousLink(Fixtures.MALFORMED_URL);
        HttpResponse<String> wrongScheme = api.createAnonymousLink(Fixtures.NON_HTTP_URL);

        assertAll(
                () -> assertEquals(400, absent.statusCode(), absent.body()),
                () -> assertEquals(400, blank.statusCode(), blank.body()),
                () -> assertEquals(400, notAUrl.statusCode(), notAUrl.body()),
                () -> assertEquals(400, wrongScheme.statusCode(), wrongScheme.body()),
                () -> assertEquals(Fixtures.INVALID_REQUEST, ApiClient.asError(notAUrl).error()),
                () -> assertTrue(namesField(notAUrl, "longUrl"),
                        "the 400 names the field, as it does on the authenticated path: "
                                + notAUrl.body()),
                () -> assertTrue(namesField(wrongScheme, "longUrl"), wrongScheme.body()));
    }

    /**
     * A target longer than the configured ceiling is refused with 400. The ceiling
     * is one of the things bounding the storage burn from an unauthenticated write
     * path, so it applies here at least as strictly as it does to a customer.
     *
     * <p>Demonstrates: AC9.
     */
    @Test
    void anOverlongTargetIsRefused() {
        String prefix = "https://example.com/";
        String atTheLimit = prefix + "a".repeat(2048 - prefix.length());
        String overTheLimit = prefix + "a".repeat(2049 - prefix.length());

        HttpResponse<String> accepted = api.createAnonymousLink(atTheLimit);
        HttpResponse<String> refused = api.createAnonymousLink(overTheLimit);

        assertAll(
                () -> assertEquals(2048, atTheLimit.length(), "the fixture sits on the boundary"),
                () -> assertEquals(2049, overTheLimit.length()),
                () -> assertEquals(201, accepted.statusCode(),
                        "the documented maximum is inclusive: " + accepted.body()),
                () -> assertEquals(400, refused.statusCode(),
                        "one character more is refused, here as on the authenticated path: "
                                + refused.body()),
                () -> assertEquals(Fixtures.INVALID_REQUEST, ApiClient.asError(refused).error()),
                () -> assertTrue(namesField(refused, "longUrl"), refused.body()));
    }

    /**
     * A denylisted target is refused with 422 {@code url_rejected}, byte-identical
     * to the refusal the authenticated path gives for the same URL.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void aDenylistedTargetIsRefused() {
        HttpResponse<String> anonymously = api.createAnonymousLink(Fixtures.DENYLISTED_URL);
        HttpResponse<String> asACustomer = api.createLink(alice(), Fixtures.DENYLISTED_URL);

        assertAll(
                () -> assertEquals(422, anonymously.statusCode(), anonymously.body()),
                () -> assertEquals(Fixtures.URL_REJECTED, ApiClient.asError(anonymously).error()),
                () -> assertEquals(
                        "The submitted URL cannot be shortened.", ApiClient.asError(anonymously).message()),
                () -> assertEquals(asACustomer.statusCode(), anonymously.statusCode(),
                        "the same denylist, reached through the same validator"),
                () -> assertEquals(asACustomer.body(), anonymously.body(),
                        "byte-identical, or one of the two paths has its own copy of the check"));
    }

    /**
     * An internal, loopback, private or self-referential target is refused with the
     * same 422 and the same message: the anonymous path is not a way around the
     * host policy.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void anInternalTargetIsRefusedWithTheSameMessageAsADenylistedOne() {
        HttpResponse<String> denylisted = api.createAnonymousLink(Fixtures.DENYLISTED_URL);
        HttpResponse<String> loopback = api.createAnonymousLink(Fixtures.LOOPBACK_URL);
        HttpResponse<String> privateSpace = api.createAnonymousLink(Fixtures.PRIVATE_HOST_URL);
        HttpResponse<String> ownOrigin = api.createAnonymousLink(Fixtures.SELF_REFERENTIAL_URL);

        assertAll(
                () -> assertEquals(422, loopback.statusCode(), loopback.body()),
                () -> assertEquals(422, privateSpace.statusCode(), privateSpace.body()),
                () -> assertEquals(422, ownOrigin.statusCode(), ownOrigin.body()),
                () -> assertEquals(denylisted.body(), loopback.body(),
                        "one message for all of them, or the response maps our network"),
                () -> assertEquals(denylisted.body(), privateSpace.body()),
                () -> assertEquals(denylisted.body(), ownOrigin.body()));
    }

    /**
     * Every equivalent-form spelling in
     * {@code Fixtures.EQUIVALENT_FORM_URLS_REFUSED_AS_UNSHORTENABLE} is refused
     * here too, and every spelling in {@code Fixtures.URLS_REFUSED_AS_MALFORMED}
     * keeps its 400. AC12 names the AC1 and AC2 forms explicitly, and this is the
     * path where a second, laxer copy of the validation would be easiest to
     * introduce and hardest to notice.
     *
     * <p>Demonstrates: AC12, AC1, AC2.
     */
    @Test
    void everyEquivalentFormEvasionIsRefusedOnTheAnonymousPathToo() {
        assertAll(
                () -> assertAll(Fixtures.EQUIVALENT_FORM_URLS_REFUSED_AS_UNSHORTENABLE.stream()
                        .map(url -> (Executable) () -> {
                            HttpResponse<String> refused = api.createAnonymousLink(url);
                            assertAll(
                                    () -> assertEquals(422, refused.statusCode(),
                                            url + " must be refused by the host policy here too: "
                                                    + refused.body()),
                                    () -> assertEquals(Fixtures.URL_REJECTED,
                                            ApiClient.asError(refused).error(), url + ": " + refused.body()),
                                    () -> assertFalse(ApiClient.asTree(refused).has("code"),
                                            "and mints nothing: " + refused.body()));
                        })),
                () -> assertAll(Fixtures.URLS_REFUSED_AS_MALFORMED.stream()
                        .map(url -> (Executable) () -> {
                            HttpResponse<String> refused = api.createAnonymousLink(url);
                            assertAll(
                                    () -> assertEquals(400, refused.statusCode(),
                                            url + " yields no host, so the syntax gate decides here as "
                                                    + "it does on the authenticated path: " + refused.body()),
                                    () -> assertEquals(Fixtures.INVALID_REQUEST,
                                            ApiClient.asError(refused).error(),
                                            url + ": " + refused.body()));
                        })));
    }

    /**
     * There is no target this endpoint accepts that the authenticated one refuses:
     * the same URL posted to both gets the same verdict, over the whole set of
     * refused and accepted targets this suite knows about. That is the general
     * form of AC12, and it is what keeps the two paths from drifting apart later.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void theAnonymousPathAcceptsNoTargetTheAuthenticatedPathRefuses() {
        String alice = alice();
        List<String> everyTargetThisSuiteKnows = new ArrayList<>();
        everyTargetThisSuiteKnows.addAll(Fixtures.EQUIVALENT_FORM_URLS_REFUSED_AS_UNSHORTENABLE);
        everyTargetThisSuiteKnows.addAll(Fixtures.URLS_REFUSED_AS_MALFORMED);
        everyTargetThisSuiteKnows.addAll(Fixtures.LOOKALIKE_URLS_STILL_ACCEPTED);
        everyTargetThisSuiteKnows.addAll(List.of(
                Fixtures.TARGET_URL,
                Fixtures.DENYLISTED_URL,
                Fixtures.LOOPBACK_URL,
                Fixtures.PRIVATE_HOST_URL,
                Fixtures.SELF_REFERENTIAL_URL,
                Fixtures.MALFORMED_URL,
                Fixtures.NON_HTTP_URL));

        assertAll(everyTargetThisSuiteKnows.stream().map(url -> (Executable) () -> {
            HttpResponse<String> anonymously = api.createAnonymousLink(url);
            HttpResponse<String> asACustomer = api.createLink(alice, url);
            assertEquals(asACustomer.statusCode(), anonymously.statusCode(),
                    "the two create paths must agree about " + url + ": authenticated said "
                            + asACustomer.statusCode() + " " + asACustomer.body()
                            + ", anonymous said " + anonymously.statusCode() + " " + anonymously.body());
        }));
    }

    /**
     * Presenting a credential changes nothing: a valid token, a forged one and no
     * token at all all produce the same 201 and an equally unowned link. The path
     * is exempt from the session filter, so there is no 401 on it by construction,
     * and a valid token must not quietly make the link somebody's.
     *
     * <p>Demonstrates: AC9, AC13.
     */
    @Test
    void aCredentialPresentedOnTheAnonymousPathChangesNothing() {
        String alice = alice();

        HttpResponse<String> withNone = api.createAnonymousLink(Fixtures.TARGET_URL);
        HttpResponse<String> withAValidOne =
                api.createAnonymousLinkWithBearer(Fixtures.TARGET_URL, alice);
        HttpResponse<String> withAForgedOne =
                api.createAnonymousLinkWithBearer(Fixtures.TARGET_URL, Fixtures.FORGED_BEARER);

        assertAll(
                () -> assertEquals(201, withNone.statusCode(), withNone.body()),
                () -> assertEquals(201, withAValidOne.statusCode(),
                        "a valid token does not change the operation: " + withAValidOne.body()),
                () -> assertEquals(201, withAForgedOne.statusCode(),
                        "and an exempt path has no 401, by construction: " + withAForgedOne.body()),
                () -> assertEquals(Optional.of(true),
                        storedLinkIsUnowned(ApiClient.asAnonymousLink(withAValidOne).code()),
                        "a token presented here must not quietly make the link somebody's"),
                () -> assertEquals(Optional.of(true),
                        storedLinkIsUnowned(ApiClient.asAnonymousLink(withAForgedOne).code())),
                () -> assertEquals(404,
                        api.getLink(alice, ApiClient.asAnonymousLink(withAValidOne).code()).statusCode(),
                        "not even to the holder of the token that was presented"));
    }

    /**
     * Two anonymous creates for the same target yield two different codes, both of
     * which work: codes are drawn from the same CSPRNG generator as the
     * authenticated path's and are not derived from the URL.
     *
     * <p>Demonstrates: AC9, AC11.
     */
    @Test
    void theSameTargetSubmittedTwiceYieldsTwoIndependentLinks() {
        AnonymousLinkResponse first = givenAnonymousLink(Fixtures.OTHER_TARGET_URL);
        AnonymousLinkResponse second = givenAnonymousLink(Fixtures.OTHER_TARGET_URL);

        HttpResponse<String> firstClick = api.click(first.code());
        HttpResponse<String> secondClick = api.click(second.code());
        assertAll(
                () -> assertNotEquals(first.code(), second.code(),
                        "a code derived from the URL would be one link, not two"),
                () -> assertEquals(302, firstClick.statusCode(), firstClick.body()),
                () -> assertEquals(302, secondClick.statusCode(), secondClick.body()),
                () -> assertEquals(Fixtures.OTHER_TARGET_URL,
                        ApiClient.header(firstClick, Fixtures.LOCATION).orElse(null)),
                () -> assertEquals(Fixtures.OTHER_TARGET_URL,
                        ApiClient.header(secondClick, Fixtures.LOCATION).orElse(null)),
                () -> assertTrue(
                        first.code().chars().allMatch(c -> ShortCodeGenerator.ALPHABET.indexOf(c) >= 0),
                        "drawn from the one generated alphabet: " + first.code()));
    }

    /**
     * The other methods on this exact path - GET, PUT, PATCH, DELETE - answer 405.
     * Not 401, because an exempted path is outside authentication for every verb,
     * and not a handler, because none exists. That absence is the only thing making
     * the exemption safe.
     *
     * <p>Demonstrates: AC9, AC17.
     */
    @Test
    void otherMethodsOnThePublicLinksPathAnswerMethodNotAllowed() {
        assertAll(List.of("GET", "PUT", "PATCH", "DELETE").stream().map(method -> (Executable) () -> {
            HttpResponse<String> answered =
                    api.send(method, ApiClient.PUBLIC_LINKS_PATH, "{}", null);
            assertAll(
                    () -> assertEquals(405, answered.statusCode(),
                            method + " " + ApiClient.PUBLIC_LINKS_PATH
                                    + " must reach the dispatcher, not a handler: " + answered.body()),
                    () -> assertNotEquals(401, answered.statusCode(),
                            "the exemption is not method-aware, so this is never a 401"),
                    () -> assertTrue(answered.statusCode() < 500,
                            method + " answered " + answered.statusCode()));
        }));
    }

    // ---- helpers ----------------------------------------------------------

    /** Scheme and authority of a short URL, which is what {@code app.base-url} fixes. */
    private String originOf(String shortUrl) {
        URI uri = URI.create(shortUrl);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /** The property names present at the top level of a response body. */
    private Set<String> propertiesOf(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    /** Whether an {@code invalid_request} body names the given field. */
    private boolean namesField(HttpResponse<String> response, String field) {
        return ApiClient.asError(response).fields() != null
                && ApiClient.asError(response).fields().containsKey(field);
    }
}
