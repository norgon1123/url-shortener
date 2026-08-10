package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Counting every click, for the life of the link (AC3), and reporting it back
 * (AC7).
 *
 * <p>"Exact" is the load-bearing word: measured, not sampled, not estimated, and
 * not lost to concurrency. A counter that is right when clicks arrive one at a
 * time and lossy when they arrive together is the defect worth catching, and it
 * does not show up one click at a time - which is why the harness can drive a
 * burst from several threads.
 */
class ClickCountingTest extends AbstractIntegrationTest {

    /**
     * After a known number of clicks, the count the owner is shown equals that
     * number exactly - not approximately, and not one flush behind.
     *
     * <p>Demonstrates: AC3, AC7.
     */
    @Test
    void theReportedCountEqualsTheNumberOfClicksServed() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        List<HttpResponse<String>> clicks = clickRepeatedly(link.code(), 7);

        assertAll(
                () -> assertTrue(clicks.stream().allMatch(r -> r.statusCode() == 302), "all seven were served"),
                () -> assertEquals(7L, reportedClickCount(alice, link.code()),
                        "the reported figure is the number of clicks served, exactly"));
    }

    /**
     * Repeated clicks from the same client all count. There is no de-duplication
     * by address, cookie or interval: the requirement asks for clicks, not unique
     * visitors.
     *
     * <p>Demonstrates: AC3, AC4.
     */
    @Test
    void repeatedClicksFromTheSameClientAllCount() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        // Same client, same address, back to back: no de-duplication window may
        // swallow any of them.
        clickRepeatedly(link.code(), 4);
        long afterFirstRun = reportedClickCount(alice, link.code());
        clickRepeatedly(link.code(), 4);
        long afterSecondRun = reportedClickCount(alice, link.code());

        assertAll(
                () -> assertEquals(4L, afterFirstRun),
                () -> assertEquals(8L, afterSecondRun, "the same client clicking again still counts"));
    }

    /**
     * A click made a moment ago is already in the reported figure, without waiting
     * for a flush interval: the number served is the durable total plus whatever
     * has not been written down yet.
     *
     * <p>Demonstrates: AC3, AC7.
     */
    @Test
    void aClickIsVisibleInTheReportedCountImmediately() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        assertEquals(302, api.click(link.code()).statusCode());
        // Read back at once: no sleep, no waiting for a flush interval.
        long immediately = reportedClickCount(alice, link.code());

        assertEquals(1L, immediately, "a click made a moment ago is already in the figure");
    }

    /**
     * Requests that do not resolve are not counted, on any code: an unknown code
     * accrues nothing, and hammering a deleted or expired link cannot inflate the
     * figure its owner is shown.
     *
     * <p>Demonstrates: AC3, AC7.
     */
    @Test
    void requestsThatDoNotResolveAreNotCounted() {
        String alice = alice();
        LinkResponse live = givenLink(alice);
        LinkResponse deleted = givenDeletedLink(alice);
        clickRepeatedly(live.code(), 2);

        List<HttpResponse<String>> atAnUnissuedCode = clickRepeatedly(Fixtures.UNISSUED_CODE, 3);
        List<HttpResponse<String>> atADeletedCode = clickRepeatedly(deleted.code(), 3);

        assertAll(
                () -> assertTrue(
                        atAnUnissuedCode.stream().allMatch(r -> r.statusCode() == 404),
                        "an unissued code does not resolve"),
                () -> assertTrue(
                        atADeletedCode.stream().allMatch(r -> r.statusCode() == 404),
                        "a deleted code does not resolve"),
                () -> assertEquals(0L, reportedClickCount(alice, deleted.code()),
                        "hammering a dead code cannot inflate its figure"),
                () -> assertEquals(2L, reportedClickCount(alice, live.code()),
                        "and it accrues nothing on any other link either"));
    }

    /**
     * A burst of concurrent clicks on one link is counted exactly - every click
     * that got a redirect is in the total, with none lost to a read-modify-write
     * race.
     *
     * <p>Demonstrates: AC3, AC22.
     */
    @Test
    void concurrentClicksOnOneLinkAreAllCounted() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        List<HttpResponse<String>> burst = clickConcurrently(link.code(), 200, 16);

        long served = burst.stream().filter(r -> r.statusCode() == 302).count();
        assertAll(
                () -> assertEquals(200L, served, "every click in the burst was served"),
                () -> assertEquals(served, reportedClickCount(alice, link.code()),
                        "none may be lost to a read-modify-write race"));
    }

    /**
     * Two links count independently: clicks on one leave the other's figure
     * untouched, including when both are clicked in the same burst.
     *
     * <p>Demonstrates: AC3, AC7.
     */
    @Test
    void twoLinksCountIndependently() {
        String alice = alice();
        LinkResponse busy = givenLink(alice, Fixtures.TARGET_URL);
        LinkResponse quiet = givenLink(alice, Fixtures.OTHER_TARGET_URL);

        clickRepeatedly(busy.code(), 5);
        assertEquals(0L, reportedClickCount(alice, quiet.code()), "sequential clicks stay on their own link");

        clickConcurrently(busy.code(), 40, 8);
        clickConcurrently(quiet.code(), 10, 8);

        assertAll(
                () -> assertEquals(45L, reportedClickCount(alice, busy.code())),
                () -> assertEquals(10L, reportedClickCount(alice, quiet.code()),
                        "counting stays per link when both take concurrent bursts"));
    }

    /**
     * A link that has gone hot - a long run of clicks on one code in a few
     * seconds - keeps answering with a redirect throughout, and the total
     * afterwards is exactly the number of redirects served.
     *
     * <p>Demonstrates: AC22, AC3.
     */
    @Test
    void aHotLinkKeepsBeingServedAndStaysExact() {
        String alice = alice();
        LinkResponse hot = givenLink(alice);

        // One code taking a long run of clicks in a few seconds: the shape AC22
        // describes, in miniature.
        List<HttpResponse<String>> burst = clickConcurrently(hot.code(), 300, 24);

        long redirects = burst.stream().filter(r -> r.statusCode() == 302).count();
        assertAll(
                () -> assertEquals(300L, redirects, "a hot link keeps being served throughout"),
                () -> assertTrue(
                        burst.stream().allMatch(r -> r.statusCode() < 500),
                        "and nothing in the run falls over"),
                () -> assertEquals(redirects, reportedClickCount(alice, hot.code()),
                        "the total afterwards is exactly the number of redirects served"));
    }

    /**
     * The count a link accrued is retained after its owner deletes it, and is
     * still reported to them: the row and its history are kept, only the redirect
     * stops.
     *
     * <p>Demonstrates: AC3, AC7, AC8.
     */
    @Test
    void theCountIsRetainedAfterTheLinkIsDeleted() {
        String alice = alice();
        LinkResponse link = givenLink(alice);
        clickRepeatedly(link.code(), 6);
        long whileLive = reportedClickCount(alice, link.code());

        assertEquals(204, api.deleteLink(alice, link.code()).statusCode());

        assertAll(
                () -> assertEquals(6L, whileLive),
                () -> assertEquals(404, api.click(link.code()).statusCode(), "only the redirect stops"),
                () -> assertEquals(6L, reportedClickCount(alice, link.code()),
                        "the count it accrued is still reported to its owner"));
    }
}
