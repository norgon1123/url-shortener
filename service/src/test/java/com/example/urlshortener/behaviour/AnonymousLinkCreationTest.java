package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Turning a long URL into a short link with no account (AC9), and everything
 * that is refused before one exists (AC12).
 *
 * <p>The response is the only copy of the link's details anyone will ever hold:
 * there is no endpoint that reads an anonymous link back, by design and
 * permanently. That makes the shape of this one body worth pinning field by
 * field, including the two fields it must not have.
 */
class AnonymousLinkCreationTest extends AbstractIntegrationTest {

    /**
     * A caller with no credentials posts a long URL and gets 201 with the code and
     * the short URL to hand out, the target unchanged, and the two instants. The
     * short URL is the configured origin plus the code, in the same form the
     * authenticated create returns.
     *
     * <p>Demonstrates: AC9.
     */
    @Test
    void anUnauthenticatedCallerReceivesAShortLinkForTheirLongUrl() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The response carries no {@code status} and no {@code clickCount}, and no
     * {@code Location} header. The two fields are absent because nobody may ever
     * read an anonymous link's numbers, and a body that carried them would invite
     * a client to expect to fetch them again from an endpoint that will only ever
     * answer 404; the header is absent because it could only point at that
     * endpoint.
     *
     * <p>Demonstrates: AC9, AC13.
     */
    @Test
    void theResponseOmitsStatusClickCountAndLocation() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The link is stored with no owner at all. This is the structural half of
     * AC13: a service that homed anonymous links on a hidden placeholder account
     * would answer 404 to every request in this suite and still be wrong, because
     * that account's list would contain them and whoever held its credentials
     * could delete them.
     *
     * <p>Demonstrates: AC9, AC13.
     */
    @Test
    void theCreatedLinkIsStoredWithNoOwner() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The expiry is one month after creation - the configured anonymous TTL,
     * applied at creation as an absolute instant - and it is reported in the
     * response so the holder knows when their link stops working.
     *
     * <p>Demonstrates: AC10.
     */
    @Test
    void anAnonymousLinkExpiresOneMonthAfterItIsCreated() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An {@code expiresAt} property is refused with 400 rather than honoured or
     * ignored. The expiry of an anonymous link is the service's to set: nobody
     * owns the link, so nobody can shorten it afterwards, and letting the creator
     * choose it once would be the only lever anyone ever had on a row no one can
     * delete.
     *
     * <p>Demonstrates: AC9, AC10.
     */
    @Test
    void anExpiresAtPropertyIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An {@code alias} property is refused with 400. Aliases share one namespace
     * with generated codes and a code is never reissued, so an unidentified caller
     * choosing memorable ones is permanent namespace squatting with no owner to
     * revoke it.
     *
     * <p>Demonstrates: AC9.
     */
    @Test
    void anAliasPropertyIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A body with no target, a blank target, or a target that is not an absolute
     * http(s) URL is refused with 400 {@code invalid_request}, exactly as the
     * authenticated path refuses the same bodies.
     *
     * <p>Demonstrates: AC9.
     */
    @Test
    void aBodyWithoutAUsableTargetIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A target longer than the configured ceiling is refused with 400. The ceiling
     * is one of the things bounding the storage burn from an unauthenticated write
     * path, so it applies here at least as strictly as it does to a customer.
     *
     * <p>Demonstrates: AC9.
     */
    @Test
    void anOverlongTargetIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A denylisted target is refused with 422 {@code url_rejected}, byte-identical
     * to the refusal the authenticated path gives for the same URL.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void aDenylistedTargetIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An internal, loopback, private or self-referential target is refused with the
     * same 422 and the same message: the anonymous path is not a way around the
     * host policy.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void anInternalTargetIsRefusedWithTheSameMessageAsADenylistedOne() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Every equivalent-form spelling in
     * {@code Fixtures.EQUIVALENT_FORM_URLS_REFUSED_AS_UNSHORTENABLE} is refused
     * here too, and every spelling in {@code Fixtures.URLS_REFUSED_AS_MALFORMED}
     * keeps its 400. AC12 names the AC1 and AC2 forms explicitly, and this is the
     * path where a second, laxer copy of the validation would be easiest to
     * introduce and hardest to notice.
     *
     * <p>Demonstrates: AC12, AC1, AC2.
     */
    @Test
    void everyEquivalentFormEvasionIsRefusedOnTheAnonymousPathToo() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * There is no target this endpoint accepts that the authenticated one refuses:
     * the same URL posted to both gets the same verdict, over the whole set of
     * refused and accepted targets this suite knows about. That is the general
     * form of AC12, and it is what keeps the two paths from drifting apart later.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void theAnonymousPathAcceptsNoTargetTheAuthenticatedPathRefuses() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Presenting a credential changes nothing: a valid token, a forged one and no
     * token at all all produce the same 201 and an equally unowned link. The path
     * is exempt from the session filter, so there is no 401 on it by construction,
     * and a valid token must not quietly make the link somebody's.
     *
     * <p>Demonstrates: AC9, AC13.
     */
    @Test
    void aCredentialPresentedOnTheAnonymousPathChangesNothing() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Two anonymous creates for the same target yield two different codes, both of
     * which work: codes are drawn from the same CSPRNG generator as the
     * authenticated path's and are not derived from the URL.
     *
     * <p>Demonstrates: AC9, AC11.
     */
    @Test
    void theSameTargetSubmittedTwiceYieldsTwoIndependentLinks() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The other methods on this exact path - GET, PUT, PATCH, DELETE - answer 405.
     * Not 401, because an exempted path is outside authentication for every verb,
     * and not a handler, because none exists. That absence is the only thing making
     * the exemption safe.
     *
     * <p>Demonstrates: AC9, AC17.
     */
    @Test
    void otherMethodsOnThePublicLinksPathAnswerMethodNotAllowed() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
