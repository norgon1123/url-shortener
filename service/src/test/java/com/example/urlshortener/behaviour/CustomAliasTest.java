package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A link created with an alias redirects exactly like a generated one: same
     * status, same headers, same target. The alias is a code, not a special case.
     *
     * <p>Demonstrates: AC5, AC2.
     */
    @Test
    void aLinkCreatedWithAnAliasRedirectsLikeAnyOther() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Reserved words are matched case-insensitively, so a differently-cased
     * spelling cannot shadow the route the reservation protects.
     *
     * <p>Demonstrates: AC5, AC6.
     */
    @Test
    void aReservedAliasIsRejectedWhateverItsCase() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An alias outside the allowed charset, or shorter or longer than the allowed
     * length, is 400 with the field named - and no link is created.
     *
     * <p>Demonstrates: AC5.
     */
    @Test
    void anAliasOutsideTheAllowedShapeIsRejected() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
