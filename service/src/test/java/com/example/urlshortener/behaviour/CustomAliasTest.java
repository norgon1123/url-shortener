package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.ApiError;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Asking for a specific short link rather than the generated one (AC5), and what
 * happens when it is not available (AC6).
 *
 * <p>Aliases and generated codes share one namespace. They have to: if they
 * lived apart, "already taken" would depend on which namespace the caller landed
 * in, and AC6's rejection would mean nothing.
 */
class CustomAliasTest extends AbstractIntegrationTest {

    /**
     * A customer who asks for an available alias gets that exact code back - not a
     * variant, not a suffixed version - and the short URL is built from it.
     *
     * <p>Demonstrates: AC5.
     */
    @Test
    void anAvailableAliasBecomesTheShortCodeExactly() {
        String alias = Fixtures.uniqueAlias(Fixtures.ALIAS);

        HttpResponse<String> response = api.createLink(alice(), Fixtures.TARGET_URL, alias, null);

        assertEquals(201, response.statusCode(), response.body());
        LinkResponse link = ApiClient.asLink(response);
        assertAll(
                () -> assertEquals(alias, link.code(), "the requested alias is the code, not a variant of it"),
                () -> assertTrue(link.shortUrl().endsWith("/" + alias), link.shortUrl()),
                () -> assertEquals(Fixtures.TARGET_URL, link.longUrl()));
    }

    /**
     * A link created with an alias redirects exactly like a generated one: same
     * status, same headers, same target. The alias is a code, not a special case.
     *
     * <p>Demonstrates: AC5, AC2.
     */
    @Test
    void aLinkCreatedWithAnAliasRedirectsLikeAnyOther() {
        String alice = alice();
        LinkResponse aliased = givenLinkWithAlias(alice, Fixtures.uniqueAlias(Fixtures.ALIAS));
        LinkResponse generated = givenLink(alice, Fixtures.TARGET_URL);

        HttpResponse<String> aliasedClick = api.click(aliased.code());
        HttpResponse<String> generatedClick = api.click(generated.code());

        assertAll(
                () -> assertEquals(302, aliasedClick.statusCode()),
                () -> assertEquals(generatedClick.statusCode(), aliasedClick.statusCode()),
                () -> assertEquals(
                        Fixtures.TARGET_URL,
                        ApiClient.header(aliasedClick, Fixtures.LOCATION).orElse(null)),
                () -> assertEquals(
                        ApiClient.header(generatedClick, Fixtures.CACHE_CONTROL),
                        ApiClient.header(aliasedClick, Fixtures.CACHE_CONTROL)),
                () -> assertEquals(1L, reportedClickCount(alice, aliased.code()),
                        "an aliased link is counted like any other"));
    }

    /**
     * Asking for an alias that is already taken is 409 {@code alias_unavailable};
     * the existing link is untouched and still points where it did. The request is
     * rejected rather than silently satisfied with a different code.
     *
     * <p>Demonstrates: AC6.
     */
    @Test
    void anAliasAlreadyInUseIsRejectedRatherThanReassigned() {
        String alice = alice();
        String alias = Fixtures.uniqueAlias(Fixtures.ALIAS);
        LinkResponse existing = givenLinkWithAlias(alice, alias);

        HttpResponse<String> second = api.createLink(alice, Fixtures.OTHER_TARGET_URL, alias, null);

        HttpResponse<String> click = api.click(alias);
        assertAll(
                () -> assertEquals(409, second.statusCode(), second.body()),
                () -> assertEquals("alias_unavailable", ApiClient.asError(second).error()),
                () -> assertEquals(
                        "That short code is not available.", ApiClient.asError(second).message()),
                () -> assertEquals(302, click.statusCode()),
                () -> assertEquals(
                        Fixtures.TARGET_URL,
                        ApiClient.header(click, Fixtures.LOCATION).orElse(null),
                        "the existing link is untouched"),
                () -> assertEquals(
                        Fixtures.TARGET_URL, ApiClient.asLink(api.getLink(alice, existing.code())).longUrl()));
    }

    /**
     * When the alias belongs to another customer the answer is the same 409 with
     * the same body: it names no owner, no target and no creation time, so a
     * conflict discloses nothing beyond "not available".
     *
     * <p>Demonstrates: AC6, AC13.
     */
    @Test
    void aConflictDisclosesNothingAboutTheExistingLink() {
        String alice = alice();
        String bob = bob();
        String alicesAlias = Fixtures.uniqueAlias(Fixtures.ALIAS);
        String bobsAlias = Fixtures.uniqueAlias(Fixtures.ALIAS);
        givenLinkWithAlias(alice, alicesAlias);
        givenLinkWithAlias(bob, bobsAlias);

        HttpResponse<String> againstAnotherCustomers =
                api.createLink(bob, Fixtures.OTHER_TARGET_URL, alicesAlias, null);
        HttpResponse<String> againstTheirOwn = api.createLink(bob, Fixtures.OTHER_TARGET_URL, bobsAlias, null);

        String disclosing = againstAnotherCustomers.body();
        assertAll(
                () -> assertEquals(409, againstAnotherCustomers.statusCode()),
                () -> assertEquals(409, againstTheirOwn.statusCode()),
                () -> assertEquals(againstTheirOwn.body(), disclosing,
                        "whose alias it is must not show through the conflict"),
                () -> assertTrue(!disclosing.contains(Fixtures.TARGET_URL), disclosing),
                () -> assertTrue(!disclosing.contains(Fixtures.ALICE.email()), disclosing),
                () -> assertTrue(!disclosing.contains(Fixtures.ALICE.id().toString()), disclosing),
                () -> assertTrue(!disclosing.contains("createdAt"), disclosing),
                () -> assertTrue(!disclosing.contains(alicesAlias), disclosing));
    }

    /**
     * The code of a deleted link is still unavailable: a soft delete keeps the
     * code, because reissuing it would silently hand an old link's audience to a
     * new owner's target.
     *
     * <p>Demonstrates: AC6, AC8.
     */
    @Test
    void theCodeOfADeletedLinkIsNeverReissued() {
        String alice = alice();
        String bob = bob();
        String alias = Fixtures.uniqueAlias(Fixtures.ALIAS);
        givenLinkWithAlias(alice, alias);
        assertEquals(204, api.deleteLink(alice, alias).statusCode());

        HttpResponse<String> byTheOwner = api.createLink(alice, Fixtures.OTHER_TARGET_URL, alias, null);
        HttpResponse<String> byAnotherCustomer = api.createLink(bob, Fixtures.OTHER_TARGET_URL, alias, null);

        HttpResponse<String> click = api.click(alias);
        assertAll(
                () -> assertEquals(409, byTheOwner.statusCode(), byTheOwner.body()),
                () -> assertEquals("alias_unavailable", ApiClient.asError(byTheOwner).error()),
                () -> assertEquals(409, byAnotherCustomer.statusCode(), byAnotherCustomer.body()),
                () -> assertEquals(404, click.statusCode(),
                        "the code stays retired: it neither resolves nor is handed to anybody else"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, click.body()));
    }

    /**
     * A reserved word is refused with 400 {@code invalid_request}, not 409: nobody
     * holds {@code actuator}, and a conflict would imply somebody does. The
     * reserved route still works afterwards.
     *
     * <p>Demonstrates: AC5, AC6.
     */
    @Test
    void aReservedAliasIsRejectedAsInvalidRatherThanAsAConflict() {
        String alice = alice();

        HttpResponse<String> response =
                api.createLink(alice, Fixtures.TARGET_URL, Fixtures.RESERVED_ALIAS, null);

        assertAll(
                () -> assertEquals(400, response.statusCode(), response.body()),
                () -> assertNotEquals(409, response.statusCode(), "nobody holds a reserved word"),
                () -> assertEquals("invalid_request", ApiClient.asError(response).error()),
                () -> assertTrue(namesField(response, "alias"), response.body()),
                // The route the reservation protects still answers as itself.
                () -> assertNotEquals(
                        302,
                        api.click(Fixtures.RESERVED_ALIAS).statusCode(),
                        "a reserved word must never resolve as a short code"));
    }

    /**
     * Reserved words are matched case-insensitively, so a differently-cased
     * spelling cannot shadow the route the reservation protects.
     *
     * <p>Demonstrates: AC5, AC6.
     */
    @Test
    void aReservedAliasIsRejectedWhateverItsCase() {
        String alice = alice();

        HttpResponse<String> mixedCase =
                api.createLink(alice, Fixtures.TARGET_URL, Fixtures.RESERVED_ALIAS_MIXED_CASE, null);
        HttpResponse<String> upperCase = api.createLink(
                alice, Fixtures.TARGET_URL, Fixtures.RESERVED_ALIAS.toUpperCase(Locale.ROOT), null);

        assertAll(
                () -> assertEquals(400, mixedCase.statusCode(), mixedCase.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(mixedCase).error()),
                () -> assertEquals(400, upperCase.statusCode(), upperCase.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(upperCase).error()),
                () -> assertEquals(404, api.click(Fixtures.RESERVED_ALIAS_MIXED_CASE).statusCode(),
                        "a differently-cased spelling must not have been issued either"));
    }

    /**
     * An alias outside the allowed charset, or shorter or longer than the allowed
     * length, is 400 with the field named - and no link is created.
     *
     * <p>Demonstrates: AC5.
     */
    @Test
    void anAliasOutsideTheAllowedShapeIsRejected() {
        String alice = alice();
        long before = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();

        HttpResponse<String> tooShort =
                api.createLink(alice, Fixtures.TARGET_URL, Fixtures.TOO_SHORT_ALIAS, null);
        HttpResponse<String> illegalCharset =
                api.createLink(alice, Fixtures.TARGET_URL, Fixtures.ILLEGAL_CHARSET_ALIAS, null);
        HttpResponse<String> tooLong = api.createLink(alice, Fixtures.TARGET_URL, "a".repeat(65), null);

        long after = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();
        assertAll(
                () -> assertEquals(400, tooShort.statusCode(), tooShort.body()),
                () -> assertTrue(namesField(tooShort, "alias"), tooShort.body()),
                () -> assertEquals(400, illegalCharset.statusCode(), illegalCharset.body()),
                () -> assertTrue(namesField(illegalCharset, "alias"), illegalCharset.body()),
                () -> assertEquals(400, tooLong.statusCode(), tooLong.body()),
                () -> assertTrue(namesField(tooLong, "alias"), tooLong.body()),
                () -> assertEquals(before, after, "none of them created a link"));
    }

    /**
     * Codes are case-sensitive: an alias differing only in case from an existing
     * one is available, and the two resolve to their own targets independently.
     * Folding case would throw away entropy from every generated code to make
     * aliases tidier.
     *
     * <p>Demonstrates: AC5, AC16.
     */
    @Test
    void codesAreCaseSensitive() {
        String alice = alice();
        String lower = Fixtures.uniqueAlias("case").toLowerCase(Locale.ROOT);
        String upper = lower.toUpperCase(Locale.ROOT);
        givenLinkWithAlias(alice, lower);

        HttpResponse<String> differingOnlyInCase =
                api.createLink(alice, Fixtures.OTHER_TARGET_URL, upper, null);

        HttpResponse<String> lowerClick = api.click(lower);
        HttpResponse<String> upperClick = api.click(upper);
        assertAll(
                () -> assertEquals(201, differingOnlyInCase.statusCode(), differingOnlyInCase.body()),
                () -> assertEquals(upper, ApiClient.asLink(differingOnlyInCase).code()),
                () -> assertEquals(
                        Fixtures.TARGET_URL, ApiClient.header(lowerClick, Fixtures.LOCATION).orElse(null)),
                () -> assertEquals(
                        Fixtures.OTHER_TARGET_URL,
                        ApiClient.header(upperClick, Fixtures.LOCATION).orElse(null),
                        "the two codes resolve independently"));
    }

    // ---- helpers ----------------------------------------------------------

    /** True when the error body names {@code field} in its per-field detail. */
    private boolean namesField(HttpResponse<String> response, String field) {
        ApiError error = ApiClient.asError(response);
        return error.fields() != null && error.fields().containsKey(field);
    }
}
