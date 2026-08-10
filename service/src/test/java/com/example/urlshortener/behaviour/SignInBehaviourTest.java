package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.api.SignInResponse;
import com.example.urlshortener.auth.CurrentCustomer;
import com.example.urlshortener.auth.JwtVerifier;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;
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
        Instant before = Instant.now();

        HttpResponse<String> response = api.signIn(Fixtures.ALICE.email(), Fixtures.ALICE.plaintext());

        assertEquals(200, response.statusCode(), response.body());
        SignInResponse session = ApiClient.asSession(response);
        assertAll(
                () -> assertTrue(
                        session.accessToken() != null && !session.accessToken().isBlank(),
                        "a session without a credential is no session"),
                () -> assertEquals("Bearer", session.tokenType()),
                () -> assertEquals(Fixtures.ALICE.id(), session.customerId()),
                () -> assertTrue(
                        session.expiresAt().isAfter(before),
                        "the expiry is an absolute instant in the future: " + session.expiresAt()));
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
        HttpResponse<String> wrongPassword =
                api.signIn(Fixtures.ALICE.email(), Fixtures.ALICE.plaintext() + "-not");
        HttpResponse<String> unknownAccount = api.signIn("nobody@example.com", Fixtures.ALICE.plaintext());

        assertAll(
                () -> assertEquals(401, wrongPassword.statusCode(), wrongPassword.body()),
                () -> assertEquals(401, unknownAccount.statusCode(), unknownAccount.body()),
                () -> assertEquals(unknownAccount.body(), wrongPassword.body(),
                        "a difference here is an account-enumeration oracle"),
                () -> assertEquals("invalid_credentials", ApiClient.asError(wrongPassword).error()),
                () -> assertEquals("Invalid email or password.", ApiClient.asError(wrongPassword).message()),
                () -> assertTrue(
                        !wrongPassword.body().contains(Fixtures.ALICE.email()),
                        "the message never echoes request content"));
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
        HttpResponse<String> missingPassword =
                api.signInRaw("{\"email\":\"" + Fixtures.ALICE.email() + "\"}");
        HttpResponse<String> unknownProperty = api.signInRaw("{\"email\":\"" + Fixtures.ALICE.email()
                + "\",\"password\":\"" + Fixtures.ALICE.plaintext() + "\",\"admin\":true}");
        HttpResponse<String> notJsonAtAll = api.signInRaw("this is not json");

        assertAll(
                () -> assertEquals(400, missingPassword.statusCode(), missingPassword.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(missingPassword).error()),
                () -> assertEquals(400, unknownProperty.statusCode(), unknownProperty.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(unknownProperty).error()),
                () -> assertNotEquals(200, unknownProperty.statusCode(),
                        "an undefined property must not be silently ignored"),
                () -> assertEquals(400, notJsonAtAll.statusCode(), notJsonAtAll.body()),
                () -> assertNotEquals(401, missingPassword.statusCode(),
                        "getting the shape wrong is not the same as getting the credentials wrong"));
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
        String oneSession = alice();

        HttpResponse<String> created = api.createLink(oneSession, Fixtures.TARGET_URL);
        LinkResponse link = ApiClient.asLink(created);
        HttpResponse<String> fetched = api.getLink(oneSession, link.code());
        HttpResponse<String> listed = api.listLinks(oneSession, 0, 5);
        HttpResponse<String> patched =
                api.updateExpiry(oneSession, link.code(), Instant.now().plusSeconds(3600));
        HttpResponse<String> deleted = api.deleteLink(oneSession, link.code());

        assertAll(
                () -> assertEquals(201, created.statusCode(), created.body()),
                () -> assertEquals(200, fetched.statusCode(), fetched.body()),
                () -> assertEquals(200, listed.statusCode(), listed.body()),
                () -> assertEquals(200, patched.statusCode(), patched.body()),
                () -> assertEquals(204, deleted.statusCode(), deleted.body()));
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
        SignInResponse session =
                ApiClient.asSession(api.signIn(Fixtures.ALICE.email(), Fixtures.ALICE.plaintext()));

        // No HTTP call, no database read: the credential is checked locally, which
        // is what makes it usable by a service that has no login system at all.
        Optional<CurrentCustomer> caller = jwtVerifier.verify(session.accessToken());

        assertAll(
                () -> assertTrue(caller.isPresent(), "the issued credential must verify locally"),
                () -> assertEquals(Fixtures.ALICE.id(), caller.orElseThrow().id()),
                () -> assertEquals(Fixtures.ALICE.email(), caller.orElseThrow().email()),
                () -> assertTrue(
                        jwtVerifier.verify(Fixtures.FORGED_BEARER).isEmpty(),
                        "a credential this service did not sign verifies against nothing"),
                () -> assertTrue(jwtVerifier.verify(Fixtures.MALFORMED_BEARER).isEmpty()));
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
        LinkResponse link = givenLink(alice());

        HttpResponse<String> absent = api.getLink(null, link.code());
        HttpResponse<String> malformed = api.getLink(Fixtures.MALFORMED_BEARER, link.code());
        HttpResponse<String> forged = api.getLink(Fixtures.FORGED_BEARER, link.code());

        assertAll(
                () -> assertEquals(401, absent.statusCode(), absent.body()),
                () -> assertEquals(401, malformed.statusCode(), malformed.body()),
                () -> assertEquals(401, forged.statusCode(), forged.body()),
                () -> assertEquals(absent.body(), malformed.body(),
                        "the reason is never disclosed, so nothing says which is nearly valid"),
                () -> assertEquals(absent.body(), forged.body()),
                () -> assertEquals("unauthorized", ApiClient.asError(absent).error()),
                () -> assertEquals("Authentication required.", ApiClient.asError(absent).message()));
    }
}
