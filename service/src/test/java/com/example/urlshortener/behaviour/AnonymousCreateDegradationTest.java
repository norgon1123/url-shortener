package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.example.urlshortener.support.TestInfrastructure;

/**
 * What the anonymous path does while a dependency is unavailable (AC15).
 *
 * <p>The rule the service already follows is that degradation is spent on
 * refusing new links, never on serving clicks, and AC15 says the anonymous path
 * is subject to it exactly as the authenticated one is. The outage is produced
 * the way the existing degradation tests produce it: the harness pauses the
 * shared Redis tier, which keeps the port mapping so the recovered half can be
 * observed too.
 *
 * <p>Note the tolerance these behaviours have to keep. A create during the outage
 * may answer 201 or 503 - the threat checker fails open by deliberate
 * configuration, so whether creation survives depends on which dependency the
 * create needed - and the claim is about which answers are permitted, not about
 * forcing one. What is not permitted is any other 5xx, and any degradation of the
 * click path.
 *
 * <p>Every method carries a timeout: the failure being looked for is a path that
 * hangs on an unreachable dependency instead of degrading, and a hang with no
 * timeout is a suite that never finishes rather than a test that fails.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class AnonymousCreateDegradationTest extends AbstractIntegrationTest {

    /** Whatever a behaviour does, the shared tier is running again before the next class. */
    @AfterEach
    void restoreSharedTier() {
        TestInfrastructure.resumeCounterTier();
    }

    /**
     * With the shared tier unreachable, an anonymous create either succeeds or is
     * refused with 503 {@code service_unavailable} - the same envelope the
     * authenticated create path uses - and never with any other server error.
     *
     * <p>Demonstrates: AC15.
     */
    @Test
    void anonymousCreationIsRefusedWithTheExistingErrorWhileADependencyIsUnavailable() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The two create paths degrade together: posting the same target to both during
     * the outage produces the same verdict, so the anonymous route neither
     * survives an outage the authenticated one does not nor fails one it survives.
     *
     * <p>Demonstrates: AC15.
     */
    @Test
    void bothCreatePathsDegradeTheSameWay() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * While anonymous creation is being refused, clicks keep being served -
     * including clicks on links that were created anonymously before the outage,
     * with the right target and the right cache headers. This is the sentence AC15
     * is made of.
     *
     * <p>Demonstrates: AC15, AC11.
     */
    @Test
    void clicksOnAnonymousLinksKeepBeingServedWhileCreationIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Nothing on the click path answers with a server error during the outage, for
     * an anonymous code any more than for an owned one - not a live code, not an
     * unknown one. 503 exists on the create paths and on no other operation.
     *
     * <p>Demonstrates: AC15.
     */
    @Test
    void theClickPathNeverAnswersWithAServerErrorForAnAnonymousCode() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * When the dependency comes back, anonymous creation works again with no
     * intervention, and the link it then mints redirects. A degraded mode nobody
     * recovers from is an outage.
     *
     * <p>Demonstrates: AC15.
     */
    @Test
    void anonymousCreationRecoversWhenTheDependencyReturns() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
