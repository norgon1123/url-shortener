package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The limit on abuse reports (AC19), which is half the defence against the
 * takedown feature being weaponised.
 *
 * <p>A report blocks a link immediately with no human in the loop, so the only
 * things standing between this endpoint and a cheap way to kill a competitor's
 * links at scale are a signed-in session and this bucket. Both are worth pinning.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            Fixtures.RATE_LIMIT_ENABLED_KEY + "=true",
            Fixtures.ABUSE_REPORT_LIMIT_KEY + "=3"
        })
class AbuseReportRateLimitTest extends AbstractIntegrationTest {

    @BeforeEach
    void startFromEmptyBuckets() {
        resetSharedTierState();
    }

    /**
     * A reporter who exceeds the limit is refused with 429, and the links named in
     * the refused reports are still redirecting - a throttled report takes nothing
     * down.
     *
     * <p>Demonstrates: AC19, AC21.
     */
    @Test
    void reportsBeyondTheLimitAreRefusedAndTakeNothingDown() {
        String alice = alice();
        String bob = bob();
        List<LinkResponse> targets = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            targets.add(givenLink(alice, Fixtures.TARGET_URL + "&target=" + i));
        }

        List<HttpResponse<String>> reports = new ArrayList<>();
        for (LinkResponse target : targets) {
            reports.add(api.reportAbuse(bob, target.code(), "Phishing page imitating a bank sign-in"));
        }

        long accepted = reports.stream().filter(r -> r.statusCode() == 202).count();
        assertAll(
                () -> assertEquals(202, reports.get(0).statusCode(), "the first report is accepted"),
                () -> assertTrue(accepted <= 3, "no more reports than the bucket holds: " + accepted),
                () -> assertTrue(
                        reports.stream().anyMatch(r -> r.statusCode() == 429),
                        "a reporter past the limit is refused"),
                // A refused report blocks nothing: those links still redirect.
                () -> assertTrue(
                        stillRedirecting(targets, reports),
                        "the links named in the refused reports are still redirecting"));
    }

    /**
     * A second customer can still report while the first is throttled: the bucket
     * is per reporter, so one abusive reporter does not disable abuse reporting
     * for everyone.
     *
     * <p>Demonstrates: AC19, AC21.
     */
    @Test
    void anotherReporterIsUnaffectedByOneReportersLimit() {
        String alice = alice();
        String bob = bob();
        List<LinkResponse> targets = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            targets.add(givenLink(alice, Fixtures.TARGET_URL + "&other=" + i));
        }

        List<HttpResponse<String>> bobsReports = new ArrayList<>();
        for (LinkResponse target : targets) {
            bobsReports.add(api.reportAbuse(bob, target.code(), null));
        }
        LinkResponse untouched = targets.get(targets.size() - 1);
        HttpResponse<String> alicesReport = api.reportAbuse(alice, untouched.code(), null);

        assertAll(
                () -> assertTrue(
                        bobsReports.stream().anyMatch(r -> r.statusCode() == 429),
                        "the first reporter is throttled"),
                () -> assertEquals(429, bobsReports.get(bobsReports.size() - 1).statusCode()),
                () -> assertEquals(202, alicesReport.statusCode(),
                        "a second customer may still report: the bucket is per reporter"),
                () -> assertEquals(404, api.click(untouched.code()).statusCode(),
                        "and that report took the link down"));
    }

    // ---- helpers ----------------------------------------------------------

    /** True when every link whose report was refused is still redirecting. */
    private boolean stillRedirecting(List<LinkResponse> targets, List<HttpResponse<String>> reports) {
        for (int i = 0; i < targets.size(); i++) {
            if (reports.get(i).statusCode() != 429) {
                continue;
            }
            if (api.click(targets.get(i).code()).statusCode() != 302) {
                return false;
            }
        }
        return true;
    }
}
