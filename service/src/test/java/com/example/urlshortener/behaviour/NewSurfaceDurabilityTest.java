package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractRestartIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * The two new surfaces survive the application going away and coming back.
 *
 * <p>Neither an account nor an anonymous link is worth anything if it lives only
 * in the process that created it, and both are unusually easy to implement that
 * way: nothing ever reads an anonymous link back, so an in-memory map would
 * satisfy every other behaviour in this suite, and an account is created by one
 * endpoint and consumed by another.
 *
 * <p>The restart mechanism belongs to the harness and is not reinvented here: it
 * stops the application and starts a new one against the same PostgreSQL and the
 * same Redis, so what the restart changes is the application process and nothing
 * else. A session issued before the restart may or may not verify after it - the
 * signing key is ephemeral when none is configured - so a behaviour that needs a
 * session afterwards signs in again, which is itself the AC5 claim worth making
 * durable: the account survives, not the token.
 */
class NewSurfaceDurabilityTest extends AbstractRestartIntegrationTest {

    /**
     * An account created before the restart signs in after it, with the same
     * credentials and the same identity. The row is what persists.
     *
     * <p>Demonstrates: AC5, AC7.
     */
    @Test
    void anAccountCreatedBeforeARestartSignsInAfterIt() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * That account's links are still theirs after the restart: they list what they
     * created and nothing else.
     *
     * <p>Demonstrates: AC5, AC8.
     */
    @Test
    void aSignedUpCustomersLinksAreStillTheirsAfterARestart() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An anonymous link created before the restart still redirects after it, to
     * the same target. Nobody can recreate one from a lost copy, so losing it on a
     * deploy would be permanent.
     *
     * <p>Demonstrates: AC9, AC11.
     */
    @Test
    void anAnonymousLinkStillRedirectsAfterARestart() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * It is still nobody's after the restart: the management API answers 404 for
     * it to every signed-in caller, exactly as it did before. Ownership does not
     * get repaired or reassigned by a boot.
     *
     * <p>Demonstrates: AC9, AC13.
     */
    @Test
    void anAnonymousLinkIsStillOwnedByNobodyAfterARestart() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An address taken before the restart is still taken after it: signing up
     * again for it is refused with the same 409. The uniqueness that AC6 needs
     * lives in the database, not in a process.
     *
     * <p>Demonstrates: AC6.
     */
    @Test
    void anAccountNameTakenBeforeARestartIsStillTakenAfterIt() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
