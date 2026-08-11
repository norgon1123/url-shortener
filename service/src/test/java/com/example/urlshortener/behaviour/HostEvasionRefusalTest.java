package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * A URL the service knows to be dangerous does not become a link, however it is
 * spelled (AC1, AC2), observed where a customer observes it: on
 * {@code POST /api/v1/links}.
 *
 * <p>These are the behaviours AC3 names. They are expected to fail against the
 * code as it stands - that is the point of writing them first - and to keep
 * passing afterwards. No test can state "this failed yesterday" about itself, so
 * the before-and-after evidence is the run record, not a method here.
 *
 * <p>The 400-versus-422 split is load-bearing and easy to get backwards, so it
 * is settled in {@code Fixtures} and not restated per test: a spelling
 * {@code java.net.URI} can extract a host from reaches the host policy and is
 * refused there with 422 {@code url_rejected}; a spelling it cannot is refused
 * earlier with 400 {@code invalid_request}, exactly as it is today. Tightening
 * the host check must not move a URL across that line in either direction.
 */
class HostEvasionRefusalTest extends AbstractIntegrationTest {

    /**
     * AC1 verbatim: {@code https://malware.example.com./x} is refused with 422
     * {@code url_rejected}, and the refusal is byte-identical to the one the same
     * host without the trailing dot already gets. Identical because a caller who
     * can tell the two apart has learned that the trailing-dot form took a
     * different route through the checks.
     *
     * <p>Demonstrates: AC1, AC3.
     */
    @Test
    void aDenylistedHostWithATrailingDotIsRefusedIdenticallyToTheHostItself() {
        String alice = alice();

        HttpResponse<String> withTheDot = api.createLink(alice, Fixtures.DENYLISTED_TRAILING_DOT_URL);
        HttpResponse<String> withoutTheDot = api.createLink(alice, Fixtures.DENYLISTED_URL);

        assertAll(
                () -> assertEquals(422, withTheDot.statusCode(), withTheDot.body()),
                () -> assertEquals(Fixtures.URL_REJECTED, ApiClient.asError(withTheDot).error()),
                () -> assertEquals(
                        "The submitted URL cannot be shortened.", ApiClient.asError(withTheDot).message()),
                () -> assertEquals(withoutTheDot.statusCode(), withTheDot.statusCode(),
                        "the trailing dot is a spelling of the same host, not a different request"),
                () -> assertEquals(withoutTheDot.body(), withTheDot.body(),
                        "byte-identical, or the response says which route the request took"));
    }

    /**
     * The same host written in mixed case with a trailing dot is refused too:
     * case and the trailing dot are both spellings of one host, not two evasions
     * to be patched one at a time.
     *
     * <p>Demonstrates: AC1, AC3.
     */
    @Test
    void aDenylistedHostInMixedCaseWithATrailingDotIsRefused() {
        String alice = alice();

        HttpResponse<String> mixedCaseWithADot =
                api.createLink(alice, Fixtures.DENYLISTED_MIXED_CASE_TRAILING_DOT_URL);
        HttpResponse<String> plain = api.createLink(alice, Fixtures.DENYLISTED_URL);

        assertAll(
                () -> assertEquals(422, mixedCaseWithADot.statusCode(), mixedCaseWithADot.body()),
                () -> assertEquals(Fixtures.URL_REJECTED, ApiClient.asError(mixedCaseWithADot).error()),
                () -> assertEquals(plain.body(), mixedCaseWithADot.body(),
                        "case and a trailing dot are spellings, not two separate evasions"));
    }

    /**
     * AC2 verbatim: {@code http://2130706433/} is refused with 422
     * {@code url_rejected}, and the refusal is byte-identical to the one
     * {@code http://127.0.0.1:9/internal} already gets.
     *
     * <p>Demonstrates: AC2, AC3.
     */
    @Test
    void anInternalAddressWrittenAsOneDecimalNumberIsRefusedIdenticallyToTheDottedForm() {
        String alice = alice();

        HttpResponse<String> asOneNumber = api.createLink(alice, Fixtures.LOOPBACK_DECIMAL_URL);
        HttpResponse<String> asADottedQuad = api.createLink(alice, Fixtures.LOOPBACK_URL);

        assertAll(
                () -> assertEquals(422, asOneNumber.statusCode(), asOneNumber.body()),
                () -> assertEquals(Fixtures.URL_REJECTED, ApiClient.asError(asOneNumber).error()),
                () -> assertEquals(
                        "The submitted URL cannot be shortened.", ApiClient.asError(asOneNumber).message()),
                () -> assertEquals(asADottedQuad.statusCode(), asOneNumber.statusCode(),
                        "2130706433 is the loopback address written as one decimal number"),
                () -> assertEquals(asADottedQuad.body(), asOneNumber.body(),
                        "and the refusal must not say which of the two spellings was used"));
    }

    /**
     * Every equivalent-form spelling in
     * {@code Fixtures.EQUIVALENT_FORM_URLS_REFUSED_AS_UNSHORTENABLE} - hexadecimal,
     * octal in one part and in four, the short numeric form of an in-range
     * address, a numeric host that is out of range, the loopback name with a
     * trailing dot, the IPv4-mapped IPv6 literal, and both denylisted hosts with
     * trailing dots - is refused with 422 and one message.
     *
     * <p>The requirement is explicit that the two confirmed bypasses are examples
     * of a weak check rather than the list to fix, so the list is what is
     * exercised, and adding a spelling to it later needs no new test.
     *
     * <p>Demonstrates: AC1, AC2, AC3.
     */
    @Test
    void everyEquivalentSpellingOfARefusedHostIsRefusedTheSameWay() {
        String alice = alice();
        String theOneRefusal = api.createLink(alice, Fixtures.DENYLISTED_URL).body();

        assertAll(Fixtures.EQUIVALENT_FORM_URLS_REFUSED_AS_UNSHORTENABLE.stream()
                .map(url -> (Executable) () -> {
                    HttpResponse<String> refused = api.createLink(alice, url);
                    assertAll(
                            () -> assertEquals(422, refused.statusCode(),
                                    url + " reached the host policy and must be refused there: "
                                            + refused.body()),
                            () -> assertEquals(Fixtures.URL_REJECTED, ApiClient.asError(refused).error(),
                                    url + ": " + refused.body()),
                            () -> assertEquals(theOneRefusal, refused.body(),
                                    "one message for every host-policy refusal, or the response is a "
                                            + "probe of the denylist: " + url));
                }));
    }

    /**
     * A refused spelling mints nothing: no code comes back, and the caller's link
     * list is no longer than it was. A check that refused the response while
     * writing the row would leave a dangerous target resolvable by anyone who
     * guessed the code.
     *
     * <p>Demonstrates: AC1, AC2.
     */
    @Test
    void aRefusedSpellingCreatesNoLink() {
        String alice = alice();
        long before = linkCountOf(alice);

        HttpResponse<String> trailingDot = api.createLink(alice, Fixtures.DENYLISTED_TRAILING_DOT_URL);
        HttpResponse<String> decimalLoopback = api.createLink(alice, Fixtures.LOOPBACK_DECIMAL_URL);
        HttpResponse<String> outOfRange = api.createLink(alice, Fixtures.OVERFLOW_NUMERIC_URL);

        long after = linkCountOf(alice);
        assertAll(
                () -> assertEquals(422, trailingDot.statusCode(), trailingDot.body()),
                () -> assertEquals(422, decimalLoopback.statusCode(), decimalLoopback.body()),
                () -> assertEquals(422, outOfRange.statusCode(), outOfRange.body()),
                () -> assertFalse(ApiClient.asTree(trailingDot).has("code"),
                        "a refusal hands back no code: " + trailingDot.body()),
                () -> assertFalse(ApiClient.asTree(decimalLoopback).has("code"),
                        decimalLoopback.body()),
                () -> assertFalse(ApiClient.asTree(outOfRange).has("code"), outOfRange.body()),
                () -> assertEquals(before, after,
                        "a refused target must not leave a row somebody could still resolve"));
    }

    /**
     * The refusal says nothing about which check fired. A denylisted host, an
     * internal address, this service's own origin and a host that cannot be
     * canonicalised all answer the same status, the same code and the same
     * message, so the endpoint cannot be used to read the denylist back.
     *
     * <p>Demonstrates: AC1, AC2.
     */
    @Test
    void theRefusalRevealsNothingAboutWhichCheckFired() {
        String alice = alice();

        HttpResponse<String> denylisted = api.createLink(alice, Fixtures.DENYLISTED_URL);
        HttpResponse<String> internal = api.createLink(alice, Fixtures.LOOPBACK_DECIMAL_URL);
        HttpResponse<String> ownOrigin = api.createLink(alice, Fixtures.SELF_REFERENTIAL_URL);
        HttpResponse<String> uncanonicalisable = api.createLink(alice, Fixtures.OVERFLOW_NUMERIC_URL);

        assertAll(
                () -> assertEquals(422, denylisted.statusCode(), denylisted.body()),
                () -> assertEquals(denylisted.statusCode(), internal.statusCode(), internal.body()),
                () -> assertEquals(denylisted.statusCode(), ownOrigin.statusCode(), ownOrigin.body()),
                () -> assertEquals(denylisted.statusCode(), uncanonicalisable.statusCode(),
                        uncanonicalisable.body()),
                () -> assertEquals(denylisted.body(), internal.body(),
                        "an internal address and a denylisted host are one answer"),
                () -> assertEquals(denylisted.body(), ownOrigin.body(),
                        "and so is our own origin"),
                () -> assertEquals(denylisted.body(), uncanonicalisable.body(),
                        "and so is a host that could not be canonicalised - it fails closed to the "
                                + "same 422 rather than to a 400 that would say the spelling did not parse"),
                () -> assertFalse(ApiClient.asTree(uncanonicalisable).has("fields"),
                        "fields belongs to invalid_request only: " + uncanonicalisable.body()));
    }

    /**
     * Every spelling in {@code Fixtures.URLS_REFUSED_AS_MALFORMED} - the forms
     * {@code java.net.URI} extracts no host from - keeps the 400
     * {@code invalid_request} it answers today. They are already refused; moving
     * them to 422 would change a documented status neither AC1 nor AC2 asks for,
     * and would additionally tell a probing caller which spellings parse.
     *
     * <p>Demonstrates: AC1, AC2, AC17.
     */
    @Test
    void aSpellingWithNoParseableHostKeepsTheRefusalItHasToday() {
        String alice = alice();

        assertAll(Fixtures.URLS_REFUSED_AS_MALFORMED.stream().map(url -> (Executable) () -> {
            HttpResponse<String> refused = api.createLink(alice, url);
            assertAll(
                    () -> assertEquals(400, refused.statusCode(),
                            url + " yields no host from java.net.URI, so the syntax gate decides "
                                    + "and the 400/422 split does not move: " + refused.body()),
                    () -> assertEquals(Fixtures.INVALID_REQUEST, ApiClient.asError(refused).error(),
                            url + ": " + refused.body()),
                    () -> assertTrue(namesField(refused, "longUrl"),
                            "a 400 names the field it is about: " + refused.body()));
        }));
    }

    /**
     * A host that merely resembles a refused one is still shortened:
     * {@code notmalware.example.com} and {@code malware.example.com.evil.test}
     * both yield links. Tightening the check must not start refusing ordinary
     * customer URLs, and a normalisation that dropped or merged a label would do
     * exactly that while every other behaviour here still passed.
     *
     * <p>Demonstrates: AC17.
     */
    @Test
    void hostsThatMerelyResembleARefusedOneAreStillShortened() {
        String alice = alice();

        assertAll(Fixtures.LOOKALIKE_URLS_STILL_ACCEPTED.stream().map(url -> (Executable) () -> {
            HttpResponse<String> created = api.createLink(alice, url);
            assertAll(
                    () -> assertEquals(201, created.statusCode(),
                            url + " is not a denylisted host and must still be shortenable - "
                                    + "over-normalisation is the failure no acceptance criterion "
                                    + "would catch: " + created.body()),
                    () -> assertEquals(url, ApiClient.asLink(created).longUrl(),
                            "and it is stored exactly as submitted"));
        }));
    }

    /**
     * A sub-domain of a denylisted host stays refused. The denylist has always
     * matched a host and its children; normalising the host must not narrow that
     * to an exact match.
     *
     * <p>Demonstrates: AC1, AC17.
     */
    @Test
    void aSubdomainOfADenylistedHostIsStillRefused() {
        String alice = alice();

        HttpResponse<String> subdomain = api.createLink(alice, Fixtures.DENYLISTED_SUBDOMAIN_URL);
        HttpResponse<String> theParentItself = api.createLink(alice, Fixtures.DENYLISTED_URL);

        assertAll(
                () -> assertEquals(422, subdomain.statusCode(),
                        "matching must stay label-based over the canonical host, not narrow to an "
                                + "exact match: " + subdomain.body()),
                () -> assertEquals(Fixtures.URL_REJECTED, ApiClient.asError(subdomain).error()),
                () -> assertEquals(theParentItself.body(), subdomain.body(),
                        "a child of a denylisted host is refused the same way its parent is"));
    }

    /**
     * An ordinary target is unaffected: the great majority of creates go through
     * the same normalisation and must be indistinguishable from before, including
     * the stored target and the {@code Location} a click sends. Normalisation is
     * for checking only and never rewrites what was submitted.
     *
     * <p>Demonstrates: AC17.
     */
    @Test
    void anOrdinaryTargetIsStoredAndServedExactlyAsSubmitted() {
        String alice = alice();
        String submitted = "https://EXAMPLE.com/A/Path?q=1&Q=2";

        HttpResponse<String> created = api.createLink(alice, submitted);
        LinkResponse link = ApiClient.asLink(created);
        HttpResponse<String> readBack = api.getLink(alice, link.code());
        HttpResponse<String> clicked = api.click(link.code());

        assertAll(
                () -> assertEquals(201, created.statusCode(), created.body()),
                () -> assertEquals(submitted, link.longUrl(),
                        "canonicalisation is for checking only and never rewrites a target"),
                () -> assertEquals(submitted, ApiClient.asLink(readBack).longUrl(),
                        "including the copy that was stored"),
                () -> assertEquals(302, clicked.statusCode(), clicked.body()),
                () -> assertEquals(submitted, ApiClient.header(clicked, Fixtures.LOCATION).orElse(null),
                        "the Location header is byte-identical to what was submitted, host case and all"));
    }

    // ---- helpers ----------------------------------------------------------

    /** How many links this customer owns, according to the service. */
    private long linkCountOf(String bearer) {
        return ApiClient.asPage(api.listLinks(bearer, 0, 1)).totalElements();
    }

    /** Whether an {@code invalid_request} body names the given field. */
    private boolean namesField(HttpResponse<String> response, String field) {
        return ApiClient.asError(response).fields() != null
                && ApiClient.asError(response).fields().containsKey(field);
    }
}
