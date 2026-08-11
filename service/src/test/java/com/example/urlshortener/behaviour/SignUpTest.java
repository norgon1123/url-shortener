package com.example.urlshortener.behaviour;

import com.example.urlshortener.auth.PasswordHasher;
import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Creating an account, and then being an ordinary customer with it (AC4, AC5,
 * AC7).
 *
 * <p>"Account name" is the email address. That is the contract's reading of an
 * ambiguity the requirement deliberately left open, and it is why sign-up posts
 * the same two fields sign-in posts. If the reading is wrong, these behaviours
 * are where it is cheapest to see it: every one of them would have to be
 * rewritten, which is the reason it had to be settled before the branches split.
 *
 * <p>Uniqueness has its own class, because "refused when it is taken" and
 * "exactly one wins a race" are a different kind of claim from "the endpoint
 * works".
 */
class SignUpTest extends AbstractIntegrationTest {

    /**
     * The component that decides what a stored credential looks like. Injected on
     * the same reasoning {@code PasswordStorageTest} injects it: AC7 is a claim
     * about a stored form, and no response can show one.
     */
    @Autowired
    protected PasswordHasher passwordHasher;

    /**
     * A caller with no credentials posts an address and a password and gets 201
     * with the account's identity: an id, the address as stored, and a creation
     * instant. The account exists from that moment.
     *
     * <p>Demonstrates: AC4.
     */
    @Test
    void anUnauthenticatedCallerCreatesAnAccountAndReceivesItsIdentity() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The 201 carries no session token and no {@code Location} header. Both
     * absences are deliberate: signing in is a separate step at the endpoint that
     * already issues tokens, and there is no {@code GET /api/v1/customers/{id}}
     * for a {@code Location} to point at, so the header would resolve for nobody
     * including the account just created.
     *
     * <p>Demonstrates: AC4.
     */
    @Test
    void theSignUpResponseCarriesNoSessionTokenAndNoLocationHeader() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The credentials just chosen work at the untouched session endpoint and
     * return a session token, with the same shape and the same status an existing
     * customer's sign-in returns. The id in the token's account is the id sign-up
     * returned.
     *
     * <p>Demonstrates: AC5.
     */
    @Test
    void theNewAccountSignsInAtTheExistingSessionEndpoint() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * That token is accepted everywhere an existing customer's token is accepted:
     * creating a link, listing links, reading one, changing its expiry, deleting
     * it and reporting abuse. AC5 says "every endpoint", so every endpoint is
     * exercised rather than one taken as representative - the failure this
     * catches is a filter or a resolver that recognises only seeded ids.
     *
     * <p>Demonstrates: AC5.
     */
    @Test
    void theNewAccountsTokenIsAcceptedByEveryEndpointAnExistingCustomersTokenIs() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Signing in with a case variant of the address just registered succeeds and
     * returns the same account: an address is an identity, not a string, and the
     * account is not duplicated by writing it differently.
     *
     * <p>Demonstrates: AC5, AC6.
     */
    @Test
    void theNewAccountSignsInWithACaseVariantOfItsAddress() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The password chosen at sign-up is not stored in recoverable form: what is
     * stored is neither the password nor contains it, in any case, and is not a
     * bare digest of it.
     *
     * <p>Demonstrates: AC7.
     */
    @Test
    void theSubmittedPasswordIsNeverStoredInRecoverableForm() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The stored form is produced by the same password-holding mechanism the
     * seeded accounts use - the same algorithm at the same parameters - and the
     * mechanism recognises the original password against it. AC7 says "the same
     * mechanism", so a second, weaker path for accounts that arrived through the
     * API is what this excludes.
     *
     * <p>Demonstrates: AC7.
     */
    @Test
    void theStoredCredentialIsProducedByTheSameMechanismTheSeededAccountsUse() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * No response on the sign-up path echoes the password, in any form: not the
     * 201, not the 400 that names a length rule it broke, not the 409 for an
     * address that is taken. An error body that quoted the value would defeat the
     * hashing on the wire instead of in the database.
     *
     * <p>Demonstrates: AC7.
     */
    @Test
    void noResponseOnTheSignUpPathEchoesThePassword() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An address that is not well formed is refused with 400
     * {@code invalid_request}, naming the field and the rule, and no account is
     * created.
     *
     * <p>Demonstrates: AC4.
     */
    @Test
    void anAddressThatIsNotWellFormedIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A password shorter than the minimum is refused with 400, naming the rule
     * and never the value; a password of exactly the minimum length is accepted.
     * The boundary is inclusive, and both sides of it are exercised because an
     * off-by-one here silently locks people out of a password they were told was
     * long enough.
     *
     * <p>Demonstrates: AC4.
     */
    @Test
    void thePasswordMinimumLengthIsInclusiveAndAnythingBelowItIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A password of exactly the maximum length is accepted and can be signed in
     * with afterwards; one character more is refused with 400. The upper boundary
     * matches the sign-in request's, so a password that can be chosen can always
     * be typed back.
     *
     * <p>Demonstrates: AC4, AC5.
     */
    @Test
    void thePasswordMaximumLengthIsInclusiveAndAnythingAboveItIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A body missing either field, or with either field blank, is refused with
     * 400 and creates nothing.
     *
     * <p>Demonstrates: AC4.
     */
    @Test
    void aBodyMissingEitherFieldIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A property this operation does not define - {@code username},
     * {@code passwordConfirm}, {@code role} - is refused with 400 rather than
     * silently dropped. Strict bodies are the existing house rule, and the one
     * that matters most here: a caller who thinks they set something they did not
     * would otherwise never find out.
     *
     * <p>Demonstrates: AC4.
     */
    @Test
    void anUnknownPropertyInTheSignUpBodyIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Presenting a credential at sign-up changes nothing: a valid token, a forged
     * one and no token at all all produce the same result. The path is exempt from
     * the session filter, so there is no 401 on it by construction, and a forged
     * token must not turn a 201 into one.
     *
     * <p>Demonstrates: AC4.
     */
    @Test
    void aCredentialPresentedAtSignUpChangesNothing() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The other methods on this exact path - GET, PUT, PATCH, DELETE - answer 405.
     * Not 401, because the path is outside authentication for every verb, and not
     * a handler, because none exists. This is the safety property that makes
     * exempting a path acceptable, and it is invisible until somebody tries.
     *
     * <p>Demonstrates: AC4, AC17.
     */
    @Test
    void otherMethodsOnTheAccountsPathAnswerMethodNotAllowed() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
