package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.AnonymousLinkResponse;
import com.example.urlshortener.api.LinkPage;
import com.example.urlshortener.support.AbstractRestartIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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
        Fixtures.NewAccount account = givenAccount();

        restartApplication();

        HttpResponse<String> signedIn = api.signIn(account.email(), account.password());
        HttpResponse<String> withTheWrongPassword =
                api.signIn(account.email(), account.password() + "x");
        assertAll(
                () -> assertEquals(200, signedIn.statusCode(),
                        "the account outlives the process that created it: " + signedIn.body()),
                () -> assertEquals(account.id(), ApiClient.asSession(signedIn).customerId(),
                        "with the same identity, not a new row under the same address"),
                () -> assertEquals(401, withTheWrongPassword.statusCode(),
                        "and the stored credential is still the one that was chosen: "
                                + withTheWrongPassword.body()));
    }

    /**
     * That account's links are still theirs after the restart: they list what they
     * created and nothing else.
     *
     * <p>Demonstrates: AC5, AC8.
     */
    @Test
    void aSignedUpCustomersLinksAreStillTheirsAfterARestart() {
        Fixtures.NewAccount account = givenAccount();
        String before = sessionFor(account);
        List<String> theirCodes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            theirCodes.add(ApiClient.asLink(
                            api.createLink(before, Fixtures.TARGET_URL + "&mine=" + i))
                    .code());
        }
        String someoneElsesCode = ApiClient.asLink(api.createLink(alice(), Fixtures.OTHER_TARGET_URL))
                .code();

        restartApplication();

        // A new session: the token is ephemeral, the account is not.
        String after = sessionFor(account);
        LinkPage listed = ApiClient.asPage(api.listLinks(after, 0, 100));
        List<String> listedCodes = listed.items().stream().map(item -> item.code()).toList();
        assertAll(
                () -> assertEquals(2L, listed.totalElements(),
                        "the links they created are still theirs: " + listedCodes),
                () -> assertTrue(listedCodes.containsAll(theirCodes), listedCodes.toString()),
                () -> assertFalse(listedCodes.contains(someoneElsesCode),
                        "and ownership was not reshuffled by the boot"),
                () -> assertEquals(200, api.getLink(after, theirCodes.get(0)).statusCode()));
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
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        assertEquals(302, api.click(anonymous.code()).statusCode(), "it redirected before the restart");

        restartApplication();

        HttpResponse<String> clicked = api.click(anonymous.code());
        assertAll(
                () -> assertEquals(302, clicked.statusCode(),
                        "an in-memory anonymous link would satisfy every other behaviour in this "
                                + "suite and fail here: " + clicked.body()),
                () -> assertEquals(Fixtures.TARGET_URL,
                        ApiClient.header(clicked, Fixtures.LOCATION).orElse(null),
                        "and to the same target"),
                () -> assertEquals(Fixtures.NO_STORE,
                        ApiClient.header(clicked, Fixtures.CACHE_CONTROL).orElse(null)));
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
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        Fixtures.NewAccount account = givenAccount();

        restartApplication();

        String alice = alice();
        String newcomer = sessionFor(account);
        HttpResponse<String> asAlice = api.getLink(alice, anonymous.code());
        HttpResponse<String> asTheNewcomer = api.getLink(newcomer, anonymous.code());
        HttpResponse<String> neverIssued = api.getLink(alice, Fixtures.UNISSUED_CODE);
        assertAll(
                () -> assertEquals(404, asAlice.statusCode(), asAlice.body()),
                () -> assertEquals(404, asTheNewcomer.statusCode(), asTheNewcomer.body()),
                () -> assertEquals(neverIssued.body(), asAlice.body(),
                        "still byte-identical to a code that was never issued"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, asAlice.body()),
                () -> assertFalse(
                        ApiClient.asPage(api.listLinks(alice, 0, 100)).items().stream()
                                .anyMatch(item -> item.code().equals(anonymous.code())),
                        "and a boot does not hand it to anybody's list"));
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
        Fixtures.NewAccount account = givenAccount();
        HttpResponse<String> refusedBefore = api.signUp(account.email(), account.password());

        restartApplication();

        HttpResponse<String> refusedAfter = api.signUp(account.email(), "a-completely-new-password");
        HttpResponse<String> theOriginalStillSignsIn =
                api.signIn(account.email(), account.password());
        assertAll(
                () -> assertEquals(409, refusedBefore.statusCode(), refusedBefore.body()),
                () -> assertEquals(409, refusedAfter.statusCode(),
                        "uniqueness lives in the database, not in a process: " + refusedAfter.body()),
                () -> assertEquals(Fixtures.ACCOUNT_UNAVAILABLE, ApiClient.asError(refusedAfter).error()),
                () -> assertEquals(refusedBefore.body(), refusedAfter.body(),
                        "and the refusal is the same one it was"),
                () -> assertEquals(200, theOriginalStillSignsIn.statusCode(),
                        "while the account itself is untouched by the refused attempt: "
                                + theOriginalStillSignsIn.body()));
    }
}
