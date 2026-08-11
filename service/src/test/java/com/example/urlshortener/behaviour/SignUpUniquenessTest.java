package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Two people never end up with the same account name (AC6).
 *
 * <p>The requirement puts the whole weight on the concurrent case - "however
 * close together they try" - which is why this is a class of its own and why the
 * harness fires its attempts from a released latch rather than a loop. A
 * read-then-write implementation passes every sequential behaviour here and
 * fails the concurrent one, and that is the only test in the suite that can tell
 * the two apart.
 *
 * <p>The refusal is 409 {@code account_unavailable}. It is worth a reviewer
 * knowing that this is the one status in the catalogue that discloses whether an
 * account exists, in a service that goes to deliberate lengths elsewhere not to:
 * AC6 requires a visible refusal, and there is no visible refusal that is not an
 * oracle. What bounds the disclosure is the IP-keyed sign-up bucket, which is why
 * that bucket has behaviours of its own.
 */
class SignUpUniquenessTest extends AbstractIntegrationTest {

    /**
     * A second sign-up for an address that already has an account is refused with
     * 409 {@code account_unavailable}, and storage still holds exactly one
     * account under that name.
     *
     * <p>Demonstrates: AC6.
     */
    @Test
    void aSecondSignUpForAnExistingAccountNameIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A case variant of an existing address is refused too. Uniqueness is over the
     * lower-cased address, because sign-in already treats case variants as one
     * account: allowing both to insert would leave a second account nobody can
     * sign in to, and a sign-in for either that fails on a non-unique result.
     *
     * <p>Demonstrates: AC6.
     */
    @Test
    void aCaseVariantOfAnExistingAccountNameIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An address that arrived by migration is as taken as one that arrived through
     * the API: signing up as a seeded customer is refused with the same 409, and
     * the seeded account is untouched by the attempt.
     *
     * <p>Demonstrates: AC6, AC16.
     */
    @Test
    void aSeededAccountNameCannotBeTakenAgain() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * When several sign-ups for one address race, exactly one answers 201 and
     * every other answers 409 - no 500, no two winners - and storage holds exactly
     * one account for that address afterwards. The last claim is the one that
     * matters: a service that returned one 201 while writing two rows would answer
     * every request in this suite correctly and still have broken AC6.
     *
     * <p>Demonstrates: AC6.
     */
    @Test
    void concurrentSignUpsForOneAccountNameLeaveExactlyOneAccount() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A refused sign-up leaves the existing account exactly as it was: the
     * original password still signs in, and the password offered by the loser does
     * not. A duplicate that quietly overwrote the stored credential would be an
     * account takeover with a 409 on it.
     *
     * <p>Demonstrates: AC6, AC7, AC16.
     */
    @Test
    void aRefusedSignUpLeavesTheExistingAccountsCredentialUntouched() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The refusal says nothing beyond "not available": not who holds the name, not
     * when it was taken, not whether it was lost to a registration two years ago
     * or to a request racing this one. The body is the catalogue entry and nothing
     * more, so the disclosure is exactly the one AC6 forces and no wider.
     *
     * <p>Demonstrates: AC6.
     */
    @Test
    void theRefusalNamesNeitherTheHolderNorWhenTheNameWasTaken() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
