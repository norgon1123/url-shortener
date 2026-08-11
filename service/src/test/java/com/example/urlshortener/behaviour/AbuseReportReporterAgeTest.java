package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.domain.LinkStatus;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The second half of the reporter-eligibility rule: the configurable minimum age.
 *
 * <p>{@code SignUpIsolationTest} pins the part of the rule that needs no
 * configuration - an account may report a link its own creation pre-dates, and may
 * not report one it does not. That alone would lock a new customer out of abuse
 * reporting forever, which is why the rule has a second limb: once an account is
 * older than {@code app.abuse.min-reporter-age} it may report links that existed
 * before it. This class is the only place that limb is observable, because the
 * production default is necessarily far longer than a test can sit through, so it
 * drives the key down to {@link Fixtures#SHORT_MIN_REPORTER_AGE_VALUE} and waits.
 *
 * <p>The two behaviours here differ only in whether the account has aged past the
 * configured minimum before it reports; everything else about them is identical,
 * so the property is demonstrably the thing deciding the outcome rather than a
 * side effect of some other difference between the callers.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = Fixtures.MIN_REPORTER_AGE_KEY + "=" + Fixtures.SHORT_MIN_REPORTER_AGE_VALUE)
class AbuseReportReporterAgeTest extends AbstractIntegrationTest {

    /** An ordinary reason, well inside the documented maximum length. */
    private static final String REASON = "Phishing page imitating a bank sign-in";

    /** How long a link is watched to show a refused report did not take it down. */
    private static final Duration STILL_UP_WINDOW = Duration.ofSeconds(3);

    /**
     * Starts from full buckets: both the sign-up bucket every behaviour here needs
     * an account from and the per-reporter bucket the report itself spends, either
     * of which an earlier class empties on purpose. This runs before the first
     * click of each test, so no click delta an assertion depends on is discarded.
     */
    @BeforeEach
    void startFromFullBuckets() {
        resetSharedTierState();
    }

    /**
     * An account that has existed for less than the configured minimum cannot take
     * down a link that already existed when it was created. The link keeps
     * redirecting and its owner still sees it as active.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void anAccountYoungerThanTheMinimumCannotTakeDownALinkItDidNotPreDate() {
        String alice = alice();
        LinkResponse alicesLink = givenLink(alice);
        String justRegistered = sessionFor(givenAccount());

        HttpResponse<String> reported = api.reportAbuse(justRegistered, alicesLink.code(), REASON);
        HttpResponse<String> againstAnUnissuedCode =
                api.reportAbuse(justRegistered, Fixtures.UNISSUED_CODE, REASON);

        Optional<Duration> stoppedRedirectingAfter =
                observeUntil(() -> api.click(alicesLink.code()).statusCode() != 302, STILL_UP_WINDOW);
        LinkResponse asItsOwnerSeesIt = ApiClient.asLink(api.getLink(alice, alicesLink.code()));
        assertAll(
                () -> assertTrue(stoppedRedirectingAfter.isEmpty(),
                        "a minutes-old account took a link down after "
                                + stoppedRedirectingAfter.orElse(null)),
                () -> assertEquals(LinkStatus.ACTIVE, asItsOwnerSeesIt.status(),
                        "an account below the minimum age blocks nothing"),
                () -> assertEquals(againstAnUnissuedCode.statusCode(), reported.statusCode(),
                        "the refusal must not depend on whether the code resolves"),
                () -> assertEquals(againstAnUnissuedCode.body(), reported.body()),
                () -> assertNotEquals(404, reported.statusCode(),
                        "a 404 here would confirm which codes exist"));
    }

    /**
     * The same account, having aged past the configured minimum, reports the same
     * kind of link and takes it down. The minimum is a delay, not a permanent
     * exclusion, and it is the configured value that decides - at the production
     * default this account would still be refused.
     *
     * <p>Demonstrates: AC8, AC17.
     */
    @Test
    void anAccountOlderThanTheMinimumCanTakeDownALinkItDidNotPreDate() {
        String alice = alice();
        LinkResponse alicesLink = givenLink(alice);
        Fixtures.NewAccount account = givenAccount();

        // The only difference from the behaviour above: this account is older than
        // the configured minimum by the time it reports.
        sleep(Fixtures.SHORT_MIN_REPORTER_AGE.plusMillis(500));
        HttpResponse<String> reported = api.reportAbuse(sessionFor(account), alicesLink.code(), REASON);

        Optional<Duration> tookEffectAfter = observeUntil(
                () -> api.click(alicesLink.code()).statusCode() == 404, Fixtures.TAKEDOWN_BOUND);
        LinkResponse asItsOwnerSeesIt = ApiClient.asLink(api.getLink(alice, alicesLink.code()));
        assertAll(
                () -> assertEquals(202, reported.statusCode(),
                        "an account past the minimum age may report any link: " + reported.body()),
                () -> assertEquals("", reported.body()),
                () -> assertTrue(tookEffectAfter.isPresent(),
                        "the reported link was still redirecting after the published bound of "
                                + Fixtures.TAKEDOWN_BOUND),
                () -> assertEquals(LinkStatus.BLOCKED, asItsOwnerSeesIt.status()));
    }
}
