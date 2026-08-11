package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.ApiError;
import com.example.urlshortener.api.LinkPage;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.domain.LinkStatus;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Listing the caller own links.
 *
 * <p>With no UI in this build, this is also the only way a customer who has lost
 * a code reaches their own link again, so it has to show them everything of
 * theirs - including what has expired, been deleted or been taken down - and
 * nothing belonging to anybody else (AC13).
 *
 * <p>The order is fixed by the contract (newest first, code ascending as the
 * tiebreak) because a blind test author needs a total order: two links created in
 * the same millisecond must not be able to swap places between runs.
 *
 * <p>The database is shared with the rest of the suite, so these tests reason
 * about the links they created and about invariants of the whole page, never
 * about an exact list of everything the customer owns.
 */
class ListLinksTest extends AbstractIntegrationTest {

    /**
     * The page contains the caller links and no link belonging to anyone else,
     * whatever the other customer has created - the query is owner-scoped, so
     * another customer link is not omitted from the list, it is invisible to it.
     *
     * <p>Demonstrates: AC13, AC7.
     */
    @Test
    void theListContainsOnlyTheCallersOwnLinks() {
        String alice = alice();
        String bob = bob();
        LinkResponse alicesFirst = givenLink(alice);
        LinkResponse alicesSecond = givenLink(alice, Fixtures.OTHER_TARGET_URL);
        LinkResponse bobsFirst = givenLink(bob);
        LinkResponse bobsSecond = givenLink(bob, Fixtures.OTHER_TARGET_URL);

        Set<String> alicesCodes = codesOf(everyLinkOf(alice));
        Set<String> bobsCodes = codesOf(everyLinkOf(bob));

        assertAll(
                () -> assertTrue(alicesCodes.contains(alicesFirst.code())),
                () -> assertTrue(alicesCodes.contains(alicesSecond.code())),
                () -> assertFalse(
                        alicesCodes.contains(bobsFirst.code()),
                        "another customer link must be invisible, not merely absent from a page"),
                () -> assertFalse(alicesCodes.contains(bobsSecond.code())),
                () -> assertTrue(bobsCodes.contains(bobsFirst.code())),
                () -> assertTrue(bobsCodes.contains(bobsSecond.code())),
                () -> assertFalse(bobsCodes.contains(alicesFirst.code())),
                () -> assertFalse(bobsCodes.contains(alicesSecond.code())));
    }

    /**
     * Links come back newest first, with the code ascending as the tiebreak, and
     * the same request twice gives the same order.
     *
     * <p>Demonstrates: AC7.
     */
    @Test
    void theListIsOrderedNewestFirstWithCodeAsTheTiebreak() {
        String alice = alice();
        LinkResponse first = givenLink(alice);
        LinkResponse second = givenLink(alice, Fixtures.OTHER_TARGET_URL);
        LinkResponse third = givenLink(alice, Fixtures.TARGET_URL + "&third=1");

        List<LinkResponse> firstRequest = ApiClient.asPage(api.listLinks(alice, 0, 20)).items();
        List<LinkResponse> secondRequest = ApiClient.asPage(api.listLinks(alice, 0, 20)).items();
        // What the contract order says these three must look like relative to one
        // another, worked out from their own createdAt and code rather than from
        // the order they happen to come back in.
        List<String> expected = contractOrderOf(List.of(first, second, third));
        List<String> observed = new ArrayList<>();
        for (LinkResponse item : firstRequest) {
            if (expected.contains(item.code())) {
                observed.add(item.code());
            }
        }

        assertAll(
                () -> assertTrue(
                        isOrderedNewestFirstThenCodeAscending(firstRequest),
                        "createdAt descending, code ascending as the tiebreak: " + codesOf(firstRequest)),
                () -> assertEquals(
                        codesOf(firstRequest).size(), firstRequest.size(), "a page must not repeat a link"),
                () -> assertEquals(expected, observed, "the three newest links in contract order"),
                () -> assertEquals(
                        firstRequest.stream().map(LinkResponse::code).toList(),
                        secondRequest.stream().map(LinkResponse::code).toList(),
                        "the same request twice gives the same order"));
    }

    /**
     * The caller expired, deleted and blocked links appear in their list with
     * their statuses and retained counts: a customer can still see what happened
     * to a link they created.
     *
     * <p>Demonstrates: AC7, AC8, AC10, AC21.
     */
    @Test
    void theListIncludesTheCallersExpiredDeletedAndBlockedLinks() {
        String alice = alice();
        String bob = bob();
        LinkResponse live = givenLink(alice);
        LinkResponse willExpire = givenLinkExpiringIn(alice, Fixtures.SHORT_EXPIRY);
        LinkResponse willBeDeleted = givenLink(alice, Fixtures.OTHER_TARGET_URL);
        LinkResponse willBeBlocked = givenLink(alice, Fixtures.TARGET_URL + "&blocked=1");
        clickRepeatedly(willExpire.code(), 1);
        clickRepeatedly(willBeDeleted.code(), 2);
        clickRepeatedly(willBeBlocked.code(), 3);
        assertEquals(204, api.deleteLink(alice, willBeDeleted.code()).statusCode());
        assertEquals(202, api.reportAbuse(bob, willBeBlocked.code(), "Phishing").statusCode());
        awaitExpiry(willExpire);

        List<LinkResponse> mine = everyLinkOf(alice);

        assertAll(
                () -> assertEquals(
                        LinkStatus.ACTIVE, statusIn(mine, live.code()), "the live one is still ACTIVE"),
                () -> assertEquals(LinkStatus.EXPIRED, statusIn(mine, willExpire.code())),
                () -> assertEquals(1L, countIn(mine, willExpire.code()), "expired keeps its count"),
                () -> assertEquals(LinkStatus.DELETED, statusIn(mine, willBeDeleted.code())),
                () -> assertEquals(2L, countIn(mine, willBeDeleted.code()), "deleted keeps its count"),
                () -> assertEquals(LinkStatus.BLOCKED, statusIn(mine, willBeBlocked.code())),
                () -> assertEquals(3L, countIn(mine, willBeBlocked.code()), "blocked keeps its count"));
    }

    /**
     * Paging walks the whole collection: consecutive pages of a given size repeat
     * nothing and skip nothing, and the reported totals agree with what was
     * actually returned.
     *
     * <p>Demonstrates: AC7.
     */
    @Test
    void pagingWalksTheCollectionWithoutRepeatingOrSkipping() {
        String alice = alice();
        for (int i = 0; i < 7; i++) {
            givenLink(alice, Fixtures.TARGET_URL + "&paging=" + i);
        }

        int size = 5;
        LinkPage firstPage = ApiClient.asPage(api.listLinks(alice, 0, size));
        List<String> walked = new ArrayList<>();
        for (int page = 0; page < firstPage.totalPages(); page++) {
            LinkPage current = ApiClient.asPage(api.listLinks(alice, page, size));
            assertEquals(page, current.page(), "the page reports which page it is");
            assertEquals(size, current.size(), "the page reports the size that was asked for");
            assertEquals(
                    firstPage.totalElements(),
                    current.totalElements(),
                    "the total does not drift while paging");
            walked.addAll(current.items().stream().map(LinkResponse::code).toList());
        }

        Set<String> distinct = new HashSet<>(walked);
        assertAll(
                () -> assertTrue(firstPage.totalElements() >= 7, "the seven just created are in there"),
                () -> assertEquals(walked.size(), distinct.size(), "no page repeats a link"),
                () -> assertEquals(
                        firstPage.totalElements(),
                        (long) walked.size(),
                        "walking every page returns exactly the reported total"),
                () -> assertEquals(
                        (int) Math.ceil(firstPage.totalElements() / (double) size),
                        firstPage.totalPages(),
                        "totalPages agrees with totalElements at this size"),
                () -> assertTrue(
                        ApiClient.asPage(api.listLinks(alice, firstPage.totalPages(), size))
                                .items()
                                .isEmpty(),
                        "a page past the end is empty rather than an error"));
    }

    /**
     * A negative page, a size below one and a size above the maximum are each 400
     * {@code invalid_request} with the offending parameter named - not silently
     * clamped, which would make client paging quietly wrong.
     *
     * <p>Demonstrates: AC7.
     */
    @Test
    void anOutOfRangePageOrSizeIsRejected() {
        String alice = alice();

        HttpResponse<String> negativePage = api.listLinks(alice, -1, 20);
        HttpResponse<String> sizeBelowOne = api.listLinks(alice, 0, 0);
        HttpResponse<String> sizeAboveTheMaximum = api.listLinks(alice, 0, 101);
        HttpResponse<String> smallestAllowedSize = api.listLinks(alice, 0, 1);
        HttpResponse<String> largestAllowedSize = api.listLinks(alice, 0, 100);

        assertAll(
                () -> assertEquals(400, negativePage.statusCode(), negativePage.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(negativePage).error()),
                () -> assertTrue(namesParameter(negativePage, "page"), negativePage.body()),
                () -> assertEquals(400, sizeBelowOne.statusCode(), sizeBelowOne.body()),
                () -> assertTrue(namesParameter(sizeBelowOne, "size"), sizeBelowOne.body()),
                () -> assertEquals(400, sizeAboveTheMaximum.statusCode(), sizeAboveTheMaximum.body()),
                () -> assertTrue(namesParameter(sizeAboveTheMaximum, "size"), sizeAboveTheMaximum.body()),
                () -> assertEquals(200, smallestAllowedSize.statusCode(), "1 is inside the range"),
                () -> assertEquals(1, ApiClient.asPage(smallestAllowedSize).size()),
                () -> assertEquals(200, largestAllowedSize.statusCode(), "100 is inside the range"),
                () -> assertEquals(100, ApiClient.asPage(largestAllowedSize).size()));
    }

    /**
     * Listing without a session is 401 {@code unauthorized}.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void listingWithoutASessionIsRefused() {
        HttpResponse<String> withNoSession = api.listLinks(null, 0, 20);
        HttpResponse<String> withAForgedSession = api.send(
                "GET", ApiClient.LINKS_PATH + "?page=0&size=20", null, Fixtures.FORGED_BEARER);

        assertAll(
                () -> assertEquals(401, withNoSession.statusCode(), withNoSession.body()),
                () -> assertEquals("unauthorized", ApiClient.asError(withNoSession).error()),
                () -> assertEquals("Authentication required.", ApiClient.asError(withNoSession).message()),
                () -> assertEquals(401, withAForgedSession.statusCode(), withAForgedSession.body()),
                () -> assertEquals(withNoSession.body(), withAForgedSession.body()));
    }

    // ---- helpers ----------------------------------------------------------

    /** Every link the customer owns, walked page by page at the largest allowed size. */
    private List<LinkResponse> everyLinkOf(String bearer) {
        List<LinkResponse> all = new ArrayList<>();
        LinkPage page = ApiClient.asPage(api.listLinks(bearer, 0, 100));
        all.addAll(page.items());
        for (int next = 1; next < page.totalPages(); next++) {
            all.addAll(ApiClient.asPage(api.listLinks(bearer, next, 100)).items());
        }
        return all;
    }

    /** The codes in a list of links. */
    private Set<String> codesOf(List<LinkResponse> links) {
        return links.stream().map(LinkResponse::code).collect(java.util.stream.Collectors.toSet());
    }

    /** The link with this code, from a list the caller already fetched. */
    private LinkResponse find(List<LinkResponse> links, String code) {
        Optional<LinkResponse> found =
                links.stream().filter(link -> link.code().equals(code)).findFirst();
        assertTrue(found.isPresent(), "the caller own link " + code + " is missing from their list");
        return found.orElseThrow();
    }

    /** The status this list reports for a code. */
    private LinkStatus statusIn(List<LinkResponse> links, String code) {
        return find(links, code).status();
    }

    /** The click count this list reports for a code. */
    private long countIn(List<LinkResponse> links, String code) {
        return find(links, code).clickCount();
    }

    /**
     * The contract order applied to a handful of links the test created: newest
     * first, code ascending where the creation instants are equal. Worked out from
     * the links own values so that the expectation does not depend on the order the
     * service happened to return.
     */
    private List<String> contractOrderOf(List<LinkResponse> links) {
        return links.stream()
                .sorted(java.util.Comparator.comparing(LinkResponse::createdAt)
                        .reversed()
                        .thenComparing(LinkResponse::code))
                .map(LinkResponse::code)
                .toList();
    }

    /** True when a page is in createdAt-descending, code-ascending order throughout. */
    private boolean isOrderedNewestFirstThenCodeAscending(List<LinkResponse> links) {
        for (int i = 1; i < links.size(); i++) {
            LinkResponse earlier = links.get(i - 1);
            LinkResponse later = links.get(i);
            if (earlier.createdAt().isBefore(later.createdAt())) {
                return false;
            }
            if (earlier.createdAt().equals(later.createdAt())
                    && earlier.code().compareTo(later.code()) > 0) {
                return false;
            }
        }
        return true;
    }

    /** True when the error body names this query parameter in its per-field detail. */
    private boolean namesParameter(HttpResponse<String> response, String parameter) {
        ApiError error = ApiClient.asError(response);
        return error.fields() != null && error.fields().containsKey(parameter);
    }
}
