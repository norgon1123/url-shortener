package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
