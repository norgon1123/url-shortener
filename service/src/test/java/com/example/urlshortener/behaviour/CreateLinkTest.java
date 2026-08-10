package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.ApiError;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.domain.LinkStatus;
import com.example.urlshortener.link.ShortCodeGenerator;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Turning a long URL into a short link (AC1), and everything that is refused
 * before one exists.
 *
 * <p>Aliases have their own class; this one is about the generated case, the
 * validation boundary between 400 and 422, and the fact that a link the service
 * would not stand behind never gets created (AC21).
 */
class CreateLinkTest extends AbstractIntegrationTest {

    /**
     * A signed-in customer posting a long URL gets 201 and a link: a code, a short
     * URL built from the configured origin plus that code, the target unchanged,
     * status ACTIVE, and a zero click count.
     *
     * <p>Demonstrates: AC1.
     */
    @Test
    void aSignedInCustomerReceivesAShortLinkForTheirLongUrl() {
        HttpResponse<String> response = api.createLink(alice(), Fixtures.TARGET_URL);

        assertEquals(201, response.statusCode(), response.body());
        LinkResponse link = ApiClient.asLink(response);
        assertAll(
                () -> assertNotNull(link.code()),
                () -> assertEquals(ShortCodeGenerator.CODE_LENGTH, link.code().length()),
                // shortUrl is the configured origin plus "/" plus the code, and
                // nothing else: no API prefix, no query, no trailing segment.
                () -> assertEquals("/" + link.code(), URI.create(link.shortUrl()).getPath()),
                () -> assertTrue(link.shortUrl().startsWith("http"), link.shortUrl()),
                () -> assertEquals(Fixtures.TARGET_URL, link.longUrl()),
                () -> assertEquals(LinkStatus.ACTIVE, link.status()),
                () -> assertNotNull(link.createdAt()),
                () -> assertNotNull(link.expiresAt()),
                () -> assertEquals(0L, link.clickCount()));
    }

    /**
     * The {@code Location} header of a create points at the API resource for the
     * new link, {@code /api/v1/links/{code}}, and fetching that location returns
     * the same link. The short URL is in the body instead, because a client pastes
     * it rather than follows it.
     *
     * <p>Demonstrates: AC1, AC7.
     */
    @Test
    void createdLinkIsAddressableAtTheReturnedLocation() {
        String alice = alice();

        HttpResponse<String> created = api.createLink(alice, Fixtures.TARGET_URL);

        assertEquals(201, created.statusCode(), created.body());
        LinkResponse link = ApiClient.asLink(created);
        String location = ApiClient.header(created, Fixtures.LOCATION).orElse(null);
        assertNotNull(location, "a create must say where the new resource lives");
        assertEquals(
                ApiClient.LINKS_PATH + "/" + link.code(),
                URI.create(location).getPath(),
                "Location is the API resource, not the short URL");

        HttpResponse<String> fetched = api.send("GET", URI.create(location).getPath(), null, alice);
        assertEquals(200, fetched.statusCode());
        assertEquals(link.code(), ApiClient.asLink(fetched).code());
        assertEquals(link.longUrl(), ApiClient.asLink(fetched).longUrl());
    }

    /**
     * The short URL in the body is exactly the configured public origin plus the
     * code, and clicking that URL is what redirects - so the string handed to a
     * customer is the string that works.
     *
     * <p>Demonstrates: AC1, AC2.
     */
    @Test
    void theReturnedShortUrlIsTheOneThatRedirects() {
        LinkResponse link = givenLink(alice(), Fixtures.OTHER_TARGET_URL);

        URI shortUrl = URI.create(link.shortUrl());
        // The string handed to the customer is an origin plus the code at the root
        // of the namespace, and requesting that path is what redirects.
        HttpResponse<String> clicked = api.rootRequest("GET", shortUrl.getPath(), null);

        assertAll(
                () -> assertTrue(shortUrl.isAbsolute(), "the short URL must be absolute: " + shortUrl),
                () -> assertEquals("/" + link.code(), shortUrl.getPath()),
                () -> assertEquals(302, clicked.statusCode()),
                () -> assertEquals(
                        Fixtures.OTHER_TARGET_URL,
                        ApiClient.header(clicked, Fixtures.LOCATION).orElse(null)));
    }

    /**
     * The same long URL submitted twice produces two different codes, each with
     * its own click count, so clicks on one do not appear on the other.
     * De-duplicating would share a count between customers and disclose that
     * someone else had already shortened that URL.
     *
     * <p>Demonstrates: AC1, AC3, AC13.
     */
    @Test
    void theSameLongUrlSubmittedTwiceYieldsTwoIndependentLinks() {
        String alice = alice();
        String bob = bob();

        LinkResponse first = givenLink(alice, Fixtures.TARGET_URL);
        LinkResponse second = givenLink(bob, Fixtures.TARGET_URL);
        clickRepeatedly(first.code(), 3);

        assertAll(
                () -> assertNotEquals(first.code(), second.code(), "nothing is de-duplicated"),
                () -> assertEquals(Fixtures.TARGET_URL, first.longUrl()),
                () -> assertEquals(Fixtures.TARGET_URL, second.longUrl()),
                () -> assertEquals(3L, reportedClickCount(alice, first.code())),
                () -> assertEquals(0L, reportedClickCount(bob, second.code()),
                        "clicks on one must not appear on the other"));
    }

    /**
     * Creating without a session is 401 {@code unauthorized}, and no link is
     * created - the collection the caller would have added to is unchanged.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void creatingWithoutASessionIsRefused() {
        String alice = alice();
        long before = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();

        HttpResponse<String> response = api.createLink(null, Fixtures.TARGET_URL);

        long after = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();
        assertAll(
                () -> assertEquals(401, response.statusCode()),
                () -> assertEquals("unauthorized", ApiClient.asError(response).error()),
                () -> assertEquals("Authentication required.", ApiClient.asError(response).message()),
                () -> assertEquals(before, after, "a refused create must not create a link"));
    }

    /**
     * A target that is not an absolute http(s) URL with a host - junk, or a
     * scheme this service does not shorten - is 400 {@code invalid_request} with
     * the offending field named. It is a fixable mistake, so the caller is told
     * which field to fix.
     *
     * <p>Demonstrates: AC1.
     */
    @Test
    void aTargetThatIsNotAnAbsoluteHttpUrlIsRejected() {
        String alice = alice();

        HttpResponse<String> junk = api.createLink(alice, Fixtures.MALFORMED_URL);
        HttpResponse<String> wrongScheme = api.createLink(alice, Fixtures.NON_HTTP_URL);
        HttpResponse<String> noHost = api.createLink(alice, "https:///no/host/at/all");

        assertAll(
                () -> assertEquals(400, junk.statusCode(), junk.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(junk).error()),
                () -> assertTrue(namesField(junk, "longUrl"), junk.body()),
                () -> assertEquals(400, wrongScheme.statusCode(), wrongScheme.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(wrongScheme).error()),
                () -> assertTrue(namesField(wrongScheme, "longUrl"), wrongScheme.body()),
                () -> assertEquals(400, noHost.statusCode(), noHost.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(noHost).error()));
    }

    /**
     * A target longer than the configured maximum is 400. The ceiling exists to
     * bound the storage a junk-link campaign can burn.
     *
     * <p>Demonstrates: AC1, AC19.
     */
    @Test
    void anOverlongTargetIsRejected() {
        String alice = alice();
        String atTheLimit = "https://example.com/" + "a".repeat(2048 - "https://example.com/".length());
        String overTheLimit = "https://example.com/" + "a".repeat(2049 - "https://example.com/".length());

        HttpResponse<String> accepted = api.createLink(alice, atTheLimit);
        HttpResponse<String> refused = api.createLink(alice, overTheLimit);

        assertAll(
                () -> assertEquals(2048, atTheLimit.length()),
                () -> assertEquals(201, accepted.statusCode(), "the documented maximum is inclusive"),
                () -> assertEquals(400, refused.statusCode(), refused.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(refused).error()),
                () -> assertTrue(namesField(refused, "longUrl"), refused.body()));
    }

    /**
     * A create body carrying a property the schema does not define is 400 rather
     * than being accepted with the extra field ignored. Strictness here is what
     * makes the target URL's immutability mechanical on the patch endpoint.
     *
     * <p>Demonstrates: AC1.
     */
    @Test
    void anUnknownPropertyInTheCreateBodyIsRejected() {
        String alice = alice();
        long before = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();

        HttpResponse<String> response = api.createLinkRaw(
                alice,
                "{\"longUrl\":\"" + Fixtures.TARGET_URL + "\",\"clickCount\":99,\"owner\":\"someone-else\"}");

        long after = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();
        assertAll(
                () -> assertEquals(400, response.statusCode(), response.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(response).error()),
                () -> assertEquals(before, after, "an undefined property is refused, not ignored"));
    }

    /**
     * An explicit expiry that is not in the future is 400, and no link is created:
     * a link that is born expired is a caller mistake, not a request for a
     * takedown.
     *
     * <p>Demonstrates: AC10, AC11.
     */
    @Test
    void anExpiryInThePastIsRejectedAtCreation() {
        String alice = alice();
        long before = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();

        HttpResponse<String> inThePast =
                api.createLink(alice, Fixtures.TARGET_URL, null, Instant.now().minusSeconds(60));

        long after = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();
        assertAll(
                () -> assertEquals(400, inThePast.statusCode(), inThePast.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(inThePast).error()),
                () -> assertTrue(namesField(inThePast, "expiresAt"), inThePast.body()),
                () -> assertEquals(before, after, "a link born expired is not created"));
    }

    /**
     * A target on the seeded threat denylist is refused with 422
     * {@code url_rejected}, and the code that would have been issued does not
     * resolve afterwards: a phishing or malware URL never becomes a working short
     * link.
     *
     * <p>Demonstrates: AC21.
     */
    @Test
    void aTargetKnownForPhishingOrMalwareNeverBecomesALink() {
        String alice = alice();
        long before = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();

        HttpResponse<String> malware = api.createLink(alice, Fixtures.DENYLISTED_URL);
        HttpResponse<String> phishing = api.createLink(alice, Fixtures.PHISHING_URL);

        long after = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();
        assertAll(
                () -> assertEquals(422, malware.statusCode(), malware.body()),
                () -> assertEquals("url_rejected", ApiClient.asError(malware).error()),
                () -> assertEquals(
                        "The submitted URL cannot be shortened.", ApiClient.asError(malware).message()),
                () -> assertEquals(422, phishing.statusCode(), phishing.body()),
                () -> assertEquals("url_rejected", ApiClient.asError(phishing).error()),
                () -> assertEquals(before, after, "no code was issued for either target"));
    }

    /**
     * A loopback, private or link-local target is refused with the same 422 and
     * the byte-identical message a denylisted target gets, so the response cannot
     * be used to map our internal network or to read back the denylist.
     *
     * <p>Demonstrates: AC21, AC13.
     */
    @Test
    void anInternalTargetIsRefusedWithTheSameMessageAsADenylistedOne() {
        String alice = alice();

        HttpResponse<String> denylisted = api.createLink(alice, Fixtures.DENYLISTED_URL);
        HttpResponse<String> loopback = api.createLink(alice, Fixtures.LOOPBACK_URL);
        HttpResponse<String> privateHost = api.createLink(alice, Fixtures.PRIVATE_HOST_URL);

        assertAll(
                () -> assertEquals(422, loopback.statusCode(), loopback.body()),
                () -> assertEquals(422, privateHost.statusCode(), privateHost.body()),
                () -> assertEquals(denylisted.body(), loopback.body(),
                        "one message for all of them, or the response maps our network"),
                () -> assertEquals(denylisted.body(), privateHost.body()),
                () -> assertEquals(denylisted.statusCode(), loopback.statusCode()),
                () -> assertEquals(denylisted.statusCode(), privateHost.statusCode()));
    }

    /**
     * A target pointing at this service's own origin is refused, so a short link
     * cannot point at a short link and build a redirect loop through us.
     *
     * <p>Demonstrates: AC21, AC20.
     */
    @Test
    void aTargetPointingBackAtThisServiceIsRefused() {
        String alice = alice();
        LinkResponse existing = givenLink(alice);

        HttpResponse<String> ownOrigin = api.createLink(alice, Fixtures.SELF_REFERENTIAL_URL);
        HttpResponse<String> ownShortUrl = api.createLink(alice, existing.shortUrl());

        assertAll(
                () -> assertEquals(422, ownOrigin.statusCode(), ownOrigin.body()),
                () -> assertEquals("url_rejected", ApiClient.asError(ownOrigin).error()),
                () -> assertEquals(422, ownShortUrl.statusCode(), ownShortUrl.body()),
                () -> assertEquals("url_rejected", ApiClient.asError(ownShortUrl).error()),
                () -> assertEquals(ownOrigin.body(), ownShortUrl.body()));
    }

    /**
     * Codes issued in a batch are all of the contracted length, drawn from the
     * base62 alphabet, all different from one another, and share no prefix or
     * other structure that would let one be derived from the rest.
     *
     * <p>Demonstrates: AC16.
     */
    @Test
    void generatedCodesAreAllDifferentAndShareNoStructure() {
        String alice = alice();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            codes.add(givenLink(alice, Fixtures.TARGET_URL + "&n=" + i).code());
        }

        Set<String> distinct = new HashSet<>(codes);
        assertAll(
                () -> assertEquals(codes.size(), distinct.size(), "issued codes must all differ: " + codes),
                () -> assertTrue(
                        codes.stream().allMatch(c -> c.length() == ShortCodeGenerator.CODE_LENGTH),
                        "every code is the contracted length: " + codes),
                () -> assertTrue(
                        codes.stream()
                                .flatMap(c -> c.chars().mapToObj(ch -> (char) ch))
                                .allMatch(ch -> ShortCodeGenerator.ALPHABET.indexOf(ch) >= 0),
                        "every code is drawn from the contracted alphabet: " + codes),
                () -> assertTrue(
                        longestSharedPrefix(codes) < 4,
                        "codes issued together must share no derivable prefix: " + codes));
    }

    // ---- helpers ----------------------------------------------------------

    /** True when the error body names {@code field} in its per-field detail. */
    private boolean namesField(HttpResponse<String> response, String field) {
        ApiError error = ApiClient.asError(response);
        return error.fields() != null && error.fields().containsKey(field);
    }

    /** The longest prefix any two of the codes have in common. */
    private int longestSharedPrefix(List<String> codes) {
        int longest = 0;
        for (int i = 0; i < codes.size(); i++) {
            for (int j = i + 1; j < codes.size(); j++) {
                int shared = 0;
                String a = codes.get(i);
                String b = codes.get(j);
                while (shared < a.length() && shared < b.length() && a.charAt(shared) == b.charAt(shared)) {
                    shared++;
                }
                longest = Math.max(longest, shared);
            }
        }
        return longest;
    }
}
