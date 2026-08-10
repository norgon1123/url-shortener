package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Turning a long URL into a short link (AC1), and everything that is refused
 * before one exists.
 *
 * <p>Aliases have their own class; this one is about the generated case, the
 * validation boundary between 400 and 422, and the fact that a link the service
 * would not stand behind never gets created (AC21).
 */
class CreateLinkTest extends AbstractIntegrationTest {

    /**
     * A signed-in customer posting a long URL gets 201 and a link: a code, a short
     * URL built from the configured origin plus that code, the target unchanged,
     * status ACTIVE, and a zero click count.
     *
     * <p>Demonstrates: AC1.
     */
    @Test
    void aSignedInCustomerReceivesAShortLinkForTheirLongUrl() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The {@code Location} header of a create points at the API resource for the
     * new link, {@code /api/v1/links/{code}}, and fetching that location returns
     * the same link. The short URL is in the body instead, because a client pastes
     * it rather than follows it.
     *
     * <p>Demonstrates: AC1, AC7.
     */
    @Test
    void createdLinkIsAddressableAtTheReturnedLocation() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The short URL in the body is exactly the configured public origin plus the
     * code, and clicking that URL is what redirects - so the string handed to a
     * customer is the string that works.
     *
     * <p>Demonstrates: AC1, AC2.
     */
    @Test
    void theReturnedShortUrlIsTheOneThatRedirects() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The same long URL submitted twice produces two different codes, each with
     * its own click count, so clicks on one do not appear on the other.
     * De-duplicating would share a count between customers and disclose that
     * someone else had already shortened that URL.
     *
     * <p>Demonstrates: AC1, AC3, AC13.
     */
    @Test
    void theSameLongUrlSubmittedTwiceYieldsTwoIndependentLinks() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Creating without a session is 401 {@code unauthorized}, and no link is
     * created - the collection the caller would have added to is unchanged.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void creatingWithoutASessionIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A target that is not an absolute http(s) URL with a host - junk, or a
     * scheme this service does not shorten - is 400 {@code invalid_request} with
     * the offending field named. It is a fixable mistake, so the caller is told
     * which field to fix.
     *
     * <p>Demonstrates: AC1.
     */
    @Test
    void aTargetThatIsNotAnAbsoluteHttpUrlIsRejected() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A target longer than the configured maximum is 400. The ceiling exists to
     * bound the storage a junk-link campaign can burn.
     *
     * <p>Demonstrates: AC1, AC19.
     */
    @Test
    void anOverlongTargetIsRejected() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A create body carrying a property the schema does not define is 400 rather
     * than being accepted with the extra field ignored. Strictness here is what
     * makes the target URL's immutability mechanical on the patch endpoint.
     *
     * <p>Demonstrates: AC1.
     */
    @Test
    void anUnknownPropertyInTheCreateBodyIsRejected() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An explicit expiry that is not in the future is 400, and no link is created:
     * a link that is born expired is a caller mistake, not a request for a
     * takedown.
     *
     * <p>Demonstrates: AC10, AC11.
     */
    @Test
    void anExpiryInThePastIsRejectedAtCreation() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A target on the seeded threat denylist is refused with 422
     * {@code url_rejected}, and the code that would have been issued does not
     * resolve afterwards: a phishing or malware URL never becomes a working short
     * link.
     *
     * <p>Demonstrates: AC21.
     */
    @Test
    void aTargetKnownForPhishingOrMalwareNeverBecomesALink() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A loopback, private or link-local target is refused with the same 422 and
     * the byte-identical message a denylisted target gets, so the response cannot
     * be used to map our internal network or to read back the denylist.
     *
     * <p>Demonstrates: AC21, AC13.
     */
    @Test
    void anInternalTargetIsRefusedWithTheSameMessageAsADenylistedOne() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A target pointing at this service's own origin is refused, so a short link
     * cannot point at a short link and build a redirect loop through us.
     *
     * <p>Demonstrates: AC21, AC20.
     */
    @Test
    void aTargetPointingBackAtThisServiceIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Codes issued in a batch are all of the contracted length, drawn from the
     * base62 alphabet, all different from one another, and share no prefix or
     * other structure that would let one be derived from the rest.
     *
     * <p>Demonstrates: AC16.
     */
    @Test
    void generatedCodesAreAllDifferentAndShareNoStructure() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
