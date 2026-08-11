package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.api.SignInResponse;
import com.example.urlshortener.api.SignUpResponse;
import com.example.urlshortener.auth.PasswordHasher;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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
     * Starts from a full sign-up bucket.
     *
     * <p>Not a rate-limit test, and that is why this is here: the sign-up bucket
     * is keyed by client address - one address for the whole suite - in the one
     * Redis every context in this JVM shares, and this class signs up more than
     * once per behaviour. A bucket another class drained, or that this class
     * drained two behaviours ago, would answer 429 where these tests expect 201,
     * and every failure would point at sign-up rather than at the throttling that
     * caused it. Throttling itself is {@code SignUpRateLimitTest}'s subject.
     *
     * <p>This runs before the first click of every test in the class, so it
     * discards no click delta any assertion here depends on.
     */
    @BeforeEach
    void startFromFullBuckets() {
        resetSharedTierState();
    }

    /**
     * A caller with no credentials posts an address and a password and gets 201
     * with the account's identity: an id, the address as stored, and a creation
     * instant. The account exists from that moment.
     *
     * <p>Demonstrates: AC4.
     */
    @Test
    void anUnauthenticatedCallerCreatesAnAccountAndReceivesItsIdentity() {
        String email = Fixtures.uniqueEmail("carol");
        Instant beforeTheRequest = Instant.now();

        HttpResponse<String> created = api.signUp(email, Fixtures.NEW_ACCOUNT_PASSWORD);

        assertEquals(201, created.statusCode(), created.body());
        SignUpResponse account = ApiClient.asAccount(created);
        assertAll(
                () -> assertNotNull(account.customerId(), "the account has an identity: " + created.body()),
                () -> assertEquals(email, account.email(), "the address as stored is returned"),
                () -> assertNotNull(account.createdAt(), created.body()),
                () -> assertFalse(account.createdAt().isBefore(beforeTheRequest.minusSeconds(60)),
                        "createdAt is when the account was created: " + account.createdAt()),
                () -> assertFalse(account.createdAt().isAfter(Instant.now().plusSeconds(60)),
                        "and not a time in the future: " + account.createdAt()),
                () -> assertEquals(1, storedAccountsNamed(email).size(),
                        "and the account really exists afterwards"));
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
        HttpResponse<String> created =
                api.signUp(Fixtures.uniqueEmail("carol"), Fixtures.NEW_ACCOUNT_PASSWORD);

        assertEquals(201, created.statusCode(), created.body());
        JsonNode body = ApiClient.asTree(created);
        assertAll(
                () -> assertTrue(ApiClient.header(created, Fixtures.LOCATION).isEmpty(),
                        "a Location here would name a path that answers 401 or 405 for every caller"),
                () -> assertFalse(body.has("accessToken"),
                        "signing in is a separate step at the endpoint that issues tokens: "
                                + created.body()),
                () -> assertFalse(body.has("token"), created.body()),
                () -> assertFalse(body.has("tokenType"), created.body()),
                () -> assertFalse(body.has("password"), created.body()),
                () -> assertFalse(body.has("passwordHash"), created.body()),
                () -> assertEquals(Set.of("customerId", "email", "createdAt"), propertiesOf(body),
                        "the created account and nothing else: " + created.body()));
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
        Fixtures.NewAccount account = givenAccount();

        HttpResponse<String> signedIn = api.signIn(account.email(), account.password());
        HttpResponse<String> seededCustomer =
                api.signIn(Fixtures.ALICE.email(), Fixtures.ALICE.plaintext());

        assertEquals(200, signedIn.statusCode(), signedIn.body());
        SignInResponse session = ApiClient.asSession(signedIn);
        assertAll(
                () -> assertEquals(seededCustomer.statusCode(), signedIn.statusCode(),
                        "the same endpoint answers the same way for an account that arrived by API"),
                () -> assertNotNull(session.accessToken(), signedIn.body()),
                () -> assertEquals("Bearer", session.tokenType(), signedIn.body()),
                () -> assertEquals(account.id(), session.customerId(),
                        "the session names the account sign-up created"),
                () -> assertTrue(session.expiresAt().isAfter(Instant.now()),
                        "the token is usable: " + session.expiresAt()),
                () -> assertEquals(401,
                        api.signIn(account.email(), account.password() + "x").statusCode(),
                        "and the credential check really runs"));
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
        String session = sessionFor(givenAccount());

        HttpResponse<String> created = api.createLink(session, Fixtures.TARGET_URL);
        LinkResponse link = ApiClient.asLink(created);
        HttpResponse<String> listed = api.listLinks(session, 0, 20);
        HttpResponse<String> read = api.getLink(session, link.code());
        HttpResponse<String> patched =
                api.updateExpiry(session, link.code(), Instant.now().plus(7, ChronoUnit.DAYS));
        HttpResponse<String> reported = api.reportAbuse(session, Fixtures.UNISSUED_CODE, "Spam");
        HttpResponse<String> deleted = api.deleteLink(session, link.code());

        assertAll(
                () -> assertEquals(201, created.statusCode(), created.body()),
                () -> assertEquals(200, listed.statusCode(), listed.body()),
                () -> assertEquals(200, read.statusCode(), read.body()),
                () -> assertEquals(200, patched.statusCode(), patched.body()),
                () -> assertEquals(202, reported.statusCode(), reported.body()),
                () -> assertEquals(204, deleted.statusCode(), deleted.body()),
                () -> assertTrue(
                        List.of(created, listed, read, patched, reported, deleted).stream()
                                .noneMatch(r -> r.statusCode() == 401),
                        "no endpoint may recognise only the ids that arrived by migration"),
                () -> assertEquals(link.code(), ApiClient.asLink(read).code(),
                        "and the link it read back is the one this account created"));
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
        Fixtures.NewAccount account = givenAccount();

        HttpResponse<String> asRegistered = api.signIn(account.email(), account.password());
        HttpResponse<String> shouted =
                api.signIn(account.email().toUpperCase(Locale.ROOT), account.password());

        assertAll(
                () -> assertEquals(200, shouted.statusCode(),
                        "an address is an identity, not a string: " + shouted.body()),
                () -> assertEquals(asRegistered.statusCode(), shouted.statusCode()),
                () -> assertEquals(account.id(), ApiClient.asSession(shouted).customerId(),
                        "and it is the same account, not a second one"),
                () -> assertEquals(1, storedAccountsNamed(account.email()).size(),
                        "written differently is not written twice"));
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
        String password = "a-password-only-this-test-chose";
        Fixtures.NewAccount account = givenAccount(Fixtures.uniqueEmail("carol"), password);

        String stored = storedPasswordHash(account.email())
                .orElseThrow(() -> new AssertionError("the account holds no credential at all"));

        assertAll(
                () -> assertNotEquals(password, stored, "the password itself is not the stored form"),
                () -> assertFalse(stored.contains(password), "nor is it inside it: " + stored),
                () -> assertFalse(
                        stored.toLowerCase(Locale.ROOT).contains(password.toLowerCase(Locale.ROOT)),
                        "nor is it there in a different case: " + stored),
                () -> assertTrue(stored.startsWith("$"),
                        "the stored form names its algorithm: " + stored),
                () -> assertTrue(stored.chars().filter(c -> c == '$').count() >= 3,
                        "and carries its parameters and salt: " + stored),
                () -> assertFalse(stored.matches("^[0-9a-fA-F]{32,128}$"),
                        "a bare hex digest is not a defensible stored credential: " + stored));
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
        String password = "another-password-only-this-test-chose";
        Fixtures.NewAccount account = givenAccount(Fixtures.uniqueEmail("carol"), password);

        String stored = storedPasswordHash(account.email())
                .orElseThrow(() -> new AssertionError("the account holds no credential at all"));
        String seeded = storedPasswordHash(Fixtures.ALICE.email())
                .orElseThrow(() -> new AssertionError("the seeded account holds no credential"));

        assertAll(
                () -> assertTrue(passwordHasher.matches(password, stored),
                        "the frozen hasher recognises the password against what was stored: " + stored),
                () -> assertFalse(passwordHasher.matches(password + "x", stored),
                        "and only that password"),
                () -> assertEquals(mechanismOf(seeded), mechanismOf(stored),
                        "same algorithm at the same parameters as the accounts that arrived by "
                                + "migration - seeded " + seeded + " against new " + stored),
                () -> assertNotEquals(seeded, stored,
                        "the same mechanism, not the same stored value"));
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
        String email = Fixtures.uniqueEmail("carol");
        String accepted = "quite-a-memorable-passphrase";
        String tooShort = "elevenchar1";
        String onTheLoser = "the-losers-own-secret-value";

        HttpResponse<String> created = api.signUp(email, accepted);
        HttpResponse<String> refusedForLength = api.signUp(Fixtures.uniqueEmail("carol"), tooShort);
        HttpResponse<String> refusedAsTaken = api.signUp(email, onTheLoser);

        assertAll(
                () -> assertEquals(201, created.statusCode(), created.body()),
                () -> assertEquals(400, refusedForLength.statusCode(), refusedForLength.body()),
                () -> assertEquals(409, refusedAsTaken.statusCode(), refusedAsTaken.body()),
                () -> assertFalse(echoes(created, accepted), "the 201 echoes the password: " + created.body()),
                () -> assertFalse(echoes(refusedForLength, tooShort),
                        "the 400 names the rule, never the value: " + refusedForLength.body()),
                () -> assertFalse(echoes(refusedAsTaken, onTheLoser),
                        "the 409 echoes the password: " + refusedAsTaken.body()));
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
        HttpResponse<String> refused =
                api.signUp(Fixtures.MALFORMED_EMAIL, Fixtures.NEW_ACCOUNT_PASSWORD);

        assertAll(
                () -> assertEquals(400, refused.statusCode(), refused.body()),
                () -> assertEquals(Fixtures.INVALID_REQUEST, ApiClient.asError(refused).error()),
                () -> assertEquals("The request is not valid.", ApiClient.asError(refused).message()),
                () -> assertTrue(namesField(refused, "email"),
                        "the field and the rule, so the caller can fix it: " + refused.body()),
                () -> assertTrue(storedAccountsNamed(Fixtures.MALFORMED_EMAIL).isEmpty(),
                        "a refused address creates nothing"));
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
        String atTheBoundary = Fixtures.uniqueEmail("carol");
        String belowIt = Fixtures.uniqueEmail("carol");

        HttpResponse<String> accepted = api.signUp(atTheBoundary, Fixtures.MIN_LENGTH_PASSWORD);
        HttpResponse<String> refused = api.signUp(belowIt, Fixtures.TOO_SHORT_PASSWORD);

        assertAll(
                () -> assertEquals(Fixtures.PASSWORD_MIN_LENGTH, Fixtures.MIN_LENGTH_PASSWORD.length(),
                        "the fixture really sits on the boundary"),
                () -> assertEquals(Fixtures.PASSWORD_MIN_LENGTH - 1, Fixtures.TOO_SHORT_PASSWORD.length(),
                        "and the other really sits one below it"),
                () -> assertEquals(201, accepted.statusCode(),
                        "the minimum is inclusive: " + accepted.body()),
                () -> assertEquals(400, refused.statusCode(),
                        "one character shorter is outside the rule: " + refused.body()),
                () -> assertEquals(Fixtures.INVALID_REQUEST, ApiClient.asError(refused).error()),
                () -> assertTrue(namesField(refused, "password"), refused.body()),
                () -> assertFalse(echoes(refused, Fixtures.TOO_SHORT_PASSWORD),
                        "naming the rule is not quoting the value: " + refused.body()),
                () -> assertTrue(storedAccountsNamed(belowIt).isEmpty(), "and creates nothing"));
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
        String atTheBoundary = Fixtures.uniqueEmail("carol");
        String aboveIt = Fixtures.uniqueEmail("carol");

        HttpResponse<String> accepted = api.signUp(atTheBoundary, Fixtures.MAX_LENGTH_PASSWORD);
        HttpResponse<String> refused = api.signUp(aboveIt, Fixtures.TOO_LONG_PASSWORD);
        HttpResponse<String> signedIn = api.signIn(atTheBoundary, Fixtures.MAX_LENGTH_PASSWORD);

        assertAll(
                () -> assertEquals(Fixtures.PASSWORD_MAX_LENGTH, Fixtures.MAX_LENGTH_PASSWORD.length()),
                () -> assertEquals(Fixtures.PASSWORD_MAX_LENGTH + 1, Fixtures.TOO_LONG_PASSWORD.length()),
                () -> assertEquals(201, accepted.statusCode(),
                        "the maximum is inclusive: " + accepted.body()),
                () -> assertEquals(200, signedIn.statusCode(),
                        "a password that can be chosen can be typed back: " + signedIn.body()),
                () -> assertEquals(400, refused.statusCode(),
                        "one character more is outside the rule: " + refused.body()),
                () -> assertTrue(namesField(refused, "password"), refused.body()),
                () -> assertTrue(storedAccountsNamed(aboveIt).isEmpty(), "and creates nothing"));
    }

    /**
     * A body missing either field, or with either field blank, is refused with
     * 400 and creates nothing.
     *
     * <p>Demonstrates: AC4.
     */
    @Test
    void aBodyMissingEitherFieldIsRefused() {
        String withNoPassword = Fixtures.uniqueEmail("carol");
        String withABlankPassword = Fixtures.uniqueEmail("carol");

        HttpResponse<String> noEmail = api.signUp(null, Fixtures.NEW_ACCOUNT_PASSWORD);
        HttpResponse<String> noPassword = api.signUp(withNoPassword, null);
        HttpResponse<String> blankEmail = api.signUp("", Fixtures.NEW_ACCOUNT_PASSWORD);
        HttpResponse<String> blankPassword = api.signUp(withABlankPassword, "");
        HttpResponse<String> nothingAtAll = api.signUpRaw("{}");

        assertAll(
                () -> assertEquals(400, noEmail.statusCode(), noEmail.body()),
                () -> assertEquals(400, noPassword.statusCode(), noPassword.body()),
                () -> assertEquals(400, blankEmail.statusCode(), blankEmail.body()),
                () -> assertEquals(400, blankPassword.statusCode(), blankPassword.body()),
                () -> assertEquals(400, nothingAtAll.statusCode(), nothingAtAll.body()),
                () -> assertEquals(Fixtures.INVALID_REQUEST, ApiClient.asError(noEmail).error()),
                () -> assertEquals(Fixtures.INVALID_REQUEST, ApiClient.asError(noPassword).error()),
                () -> assertTrue(storedAccountsNamed(withNoPassword).isEmpty(),
                        "an account with no password must not exist"),
                () -> assertTrue(storedAccountsNamed(withABlankPassword).isEmpty(),
                        "and neither must one with a blank password"));
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
        List<String> emails = new ArrayList<>();
        List<HttpResponse<String>> refusals = new ArrayList<>();
        for (String extra : List.of(
                "\"username\":\"carol\"", "\"passwordConfirm\":\"whatever-else\"", "\"role\":\"admin\"")) {
            String email = Fixtures.uniqueEmail("carol");
            emails.add(email);
            refusals.add(api.signUpRaw("{\"email\":\"" + email + "\",\"password\":\""
                    + Fixtures.NEW_ACCOUNT_PASSWORD + "\"," + extra + "}"));
        }

        assertAll(
                () -> assertAll(refusals.stream().map(refused -> (Executable) () -> assertAll(
                        () -> assertEquals(400, refused.statusCode(),
                                "a property this operation does not define is refused, not dropped: "
                                        + refused.body()),
                        () -> assertEquals(Fixtures.INVALID_REQUEST, ApiClient.asError(refused).error(),
                                refused.body())))),
                () -> assertTrue(emails.stream().allMatch(e -> storedAccountsNamed(e).isEmpty()),
                        "and a refused body creates nothing"));
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
        String alice = alice();

        HttpResponse<String> withNoCredential =
                api.signUp(Fixtures.uniqueEmail("carol"), Fixtures.NEW_ACCOUNT_PASSWORD);
        HttpResponse<String> withAValidOne = api.signUpWithBearer(
                Fixtures.uniqueEmail("carol"), Fixtures.NEW_ACCOUNT_PASSWORD, alice);
        HttpResponse<String> withAForgedOne = api.signUpWithBearer(
                Fixtures.uniqueEmail("carol"), Fixtures.NEW_ACCOUNT_PASSWORD, Fixtures.FORGED_BEARER);
        HttpResponse<String> withNonsense = api.signUpWithBearer(
                Fixtures.uniqueEmail("carol"), Fixtures.NEW_ACCOUNT_PASSWORD, Fixtures.MALFORMED_BEARER);

        assertAll(
                () -> assertEquals(201, withNoCredential.statusCode(), withNoCredential.body()),
                () -> assertEquals(201, withAValidOne.statusCode(),
                        "a valid token must not change the operation: " + withAValidOne.body()),
                () -> assertEquals(201, withAForgedOne.statusCode(),
                        "an exempt path is outside authentication, so a forged token is not a 401: "
                                + withAForgedOne.body()),
                () -> assertEquals(201, withNonsense.statusCode(), withNonsense.body()),
                () -> assertTrue(
                        List.of(withAValidOne, withAForgedOne, withNonsense).stream()
                                .noneMatch(r -> r.statusCode() == 401),
                        "there is no 401 on this operation, by construction"),
                () -> assertNotEquals(
                        ApiClient.asAccount(withAValidOne).customerId(), Fixtures.ALICE.id(),
                        "and the credential does not decide whose account is created"));
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
        assertAll(List.of("GET", "PUT", "PATCH", "DELETE").stream().map(method -> (Executable) () -> {
            HttpResponse<String> answered =
                    api.send(method, ApiClient.CUSTOMERS_PATH, "{}", null);
            assertAll(
                    () -> assertEquals(405, answered.statusCode(),
                            method + " " + ApiClient.CUSTOMERS_PATH
                                    + " must reach the dispatcher, not a handler: " + answered.body()),
                    () -> assertNotEquals(401, answered.statusCode(),
                            "the exemption is not method-aware, so this is never a 401"),
                    () -> assertTrue(answered.statusCode() < 500,
                            method + " answered " + answered.statusCode()));
        }));
    }

    // ---- helpers ----------------------------------------------------------

    /** The property names present at the top level of a response body. */
    private Set<String> propertiesOf(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    /** Whether an {@code invalid_request} body names the given field. */
    private boolean namesField(HttpResponse<String> response, String field) {
        return ApiClient.asError(response).fields() != null
                && ApiClient.asError(response).fields().containsKey(field);
    }

    /** Whether a response body carries the given secret anywhere, in any case. */
    private boolean echoes(HttpResponse<String> response, String secret) {
        return response.body() != null
                && response.body().toLowerCase(Locale.ROOT).contains(secret.toLowerCase(Locale.ROOT));
    }

    /**
     * The algorithm and parameter segments of a stored credential - everything
     * before the salt - which is what "the same mechanism at the same parameters"
     * means for a credential whose salt is different every time.
     */
    private String mechanismOf(String storedHash) {
        String[] segments = storedHash.split("\\$");
        return segments.length >= 4
                ? String.join("$", segments[1], segments[2], segments[3])
                : storedHash;
    }

}
