package com.example.urlshortener.behaviour;

import com.example.urlshortener.link.ShortCodeGenerator;
import org.junit.jupiter.api.Test;

/**
 * That a short code cannot be guessed, and that holding a pile of issued codes
 * gives no purchase on the next one (AC16).
 *
 * <p>AC16 is a statement about a distribution, and a distribution is not visible
 * from one HTTP response: an implementation that issued sequential codes would
 * satisfy every black-box test in this suite. So this class examines the
 * generator directly - it is a frozen part of the contract, with its length and
 * alphabet fixed as constants - and the black-box half of the same criterion (a
 * sweep of guesses finds nothing and gets throttled) lives with the other click
 * path tests.
 *
 * <p>No Spring context: the generator is a plain object, and starting an
 * application to draw random strings would only make the suite slower.
 */
class ShortCodeUnguessabilityTest {

    /**
     * Every generated code is exactly the contracted length and uses only the
     * contracted alphabet, so the code space is the size the entropy claim
     * assumes.
     *
     * <p>Demonstrates: AC16.
     */
    @Test
    void everyCodeHasTheContractedLengthAndAlphabet() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A large sample contains no repeat and no two codes sharing a long prefix:
     * codes are not derived from a counter, a timestamp, the row, the target URL
     * or one another.
     *
     * <p>Demonstrates: AC16.
     */
    @Test
    void codesInALargeSampleNeitherRepeatNorShareStructure() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Each position draws from the whole alphabet rather than favouring its first
     * characters, which is what a modulo of a random integer would do - a bias
     * that quietly costs entropy and would not show up in any other test.
     *
     * <p>Demonstrates: AC16.
     */
    @Test
    void everyPositionDrawsFromTheWholeAlphabetWithoutBias() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Two generators seeded from different sources produce different codes, and
     * the sequence is not reproducible from anything an attacker can see: the
     * source is a cryptographic one, not a seeded pseudo-random number generator.
     *
     * <p>Demonstrates: AC16.
     */
    @Test
    void theSequenceIsNotReproducibleFromWhatAnAttackerCanSee() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The contract's own constants agree with each other: the code length is
     * enough to carry the stated entropy over the stated alphabet. If someone
     * shortens the code to make links prettier, this is the test that says what
     * was spent.
     *
     * <p>Demonstrates: AC16.
     */
    @Test
    void theCodeLengthCarriesTheStatedEntropy() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /** Referenced so the frozen generator is on this class's compile path. */
    protected ShortCodeGenerator generator() {
        return new ShortCodeGenerator();
    }
}
