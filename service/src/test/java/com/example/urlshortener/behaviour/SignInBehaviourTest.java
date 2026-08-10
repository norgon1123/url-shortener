package com.example.urlshortener.behaviour;

import com.example.urlshortener.auth.JwtVerifier;
import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Signing in, and what a session is worth afterwards.
 *
 * <p>Covers AC12 (work without a valid session is refused), AC17 (the stored
 * form of a credential does not give it back) as it shows on the wire, and AC18
 * (a session is carried by the request, not looked up per request).
 */
class SignInBehaviourTest extends AbstractIntegrationTest {

    /**
     * The bean that turns a bearer credential into a caller. Injected so that
     * AC18's "no call back to the login system" can be shown at the only place it
     * is observable in a single-service build: the check is a local one over the
     * token itself.
     */
    @Autowired
    protected JwtVerifier jwtVerifier;

    /**
     * A seeded customer signing in with the right credentials gets 200 and a
     * session: a bearer credential, the {@code Bearer} type, an absolute expiry
     * roughly {@code app.session.ttl} out, and their own customer id.
     *
     * <p>Demonstrates: AC12, AC18.
     */
    @Test
    void validCredentialsIssueASession() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A wrong password for a real account and any password for an account that
     * does not exist both answer 401 with the identical {@code invalid_credentials}
     * body. Nothing in the response distinguishes the two, because a difference is
     * an account-enumeration oracle.
     *
     * <p>Demonstrates: AC17.
     */
    @Test
    void unknownAccountAndWrongPasswordAreRefusedIdentically() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A sign-in body missing a required field, or carrying a property the schema
     * does not define, is 400 {@code invalid_request} - never 401, which would
     * confuse "you got the shape wrong" with "those credentials are wrong".
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void malformedSignInBodyIsRejectedAsAnInvalidRequest() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * One sign-in is enough: the credential it returns is accepted on subsequent
     * management calls without signing in again, and the same credential works for
     * several different requests in a row.
     *
     * <p>Demonstrates: AC18.
     */
    @Test
    void oneSessionServesManyLaterRequests() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The credential is checked locally against the signing key: handing the
     * issued credential straight to {@link JwtVerifier} yields the customer it was
     * issued for, with no database read and no call back to the sign-in endpoint.
     * That is what makes AC18 true of every service that receives the token, not
     * just of this one.
     *
     * <p>Demonstrates: AC18.
     */
    @Test
    void aSessionIsCheckedAgainstTheKeyRatherThanTheLoginSystem() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * No credential, a credential that is not a JWS, and a well-shaped credential
     * this service did not sign all answer 401 with the identical
     * {@code unauthorized} body. The reason is never disclosed, so a caller cannot
     * learn which credentials are nearly valid.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void absentMalformedAndForgedCredentialsAreRefusedIdentically() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
