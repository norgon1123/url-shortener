package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Two links count independently: clicks on one leave the other's figure
     * untouched, including when both are clicked in the same burst.
     *
     * <p>Demonstrates: AC3, AC7.
     */
    @Test
    void twoLinksCountIndependently() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
