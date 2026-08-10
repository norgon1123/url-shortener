package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.link.ShortCodeGenerator;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Enough draws that a missing alphabet character at any position is not luck. */
    private static final int LARGE_SAMPLE = 5000;

    /**
     * Every generated code is exactly the contracted length and uses only the
     * contracted alphabet, so the code space is the size the entropy claim
     * assumes.
     *
     * <p>Demonstrates: AC16.
     */
    @Test
    void everyCodeHasTheContractedLengthAndAlphabet() {
        List<String> codes = sample(generator(), 500);

        assertAll(
                () -> assertTrue(
                        codes.stream().allMatch(c -> c.length() == ShortCodeGenerator.CODE_LENGTH),
                        "a code of the wrong length shrinks the space the entropy claim assumes: "
                                + codes.stream()
                                        .filter(c -> c.length() != ShortCodeGenerator.CODE_LENGTH)
                                        .toList()),
                () -> assertTrue(
                        codes.stream().allMatch(this::drawnFromTheAlphabet),
                        "a character outside the alphabet is a code that may not survive a URL: "
                                + codes.stream().filter(c -> !drawnFromTheAlphabet(c)).toList()),
                () -> assertEquals(62, ShortCodeGenerator.ALPHABET.length(), "base62, as contracted"));
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
        List<String> codes = sample(generator(), LARGE_SAMPLE);

        Set<String> distinct = new HashSet<>(codes);
        // A counter, a timestamp or anything else monotonic shows up here: the
        // leading characters would move slowly and collide constantly.
        Set<String> leadingSixCharacters = new HashSet<>(codes.stream().map(c -> c.substring(0, 6)).toList());
        Set<String> trailingSixCharacters =
                new HashSet<>(codes.stream().map(c -> c.substring(c.length() - 6)).toList());
        assertAll(
                () -> assertEquals(codes.size(), distinct.size(),
                        "a repeat in " + LARGE_SAMPLE + " draws over a 62^"
                                + ShortCodeGenerator.CODE_LENGTH + " space is not chance"),
                () -> assertEquals(codes.size(), leadingSixCharacters.size(),
                        "two codes sharing their first six characters means the prefix is derived"),
                () -> assertEquals(codes.size(), trailingSixCharacters.size(),
                        "and the same at the other end, where a counter usually hides"));
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
        List<String> codes = sample(generator(), LARGE_SAMPLE);
        int alphabetSize = ShortCodeGenerator.ALPHABET.length();

        // Which characters ever appear at each position.
        List<Set<Character>> seenAtPosition = new ArrayList<>();
        for (int position = 0; position < ShortCodeGenerator.CODE_LENGTH; position++) {
            Set<Character> seen = new HashSet<>();
            for (String code : codes) {
                seen.add(code.charAt(position));
            }
            seenAtPosition.add(seen);
        }

        // How often each character appears overall. A `nextInt(256) % 62` draw
        // gives the first eight characters of the alphabet 25% more weight than
        // the rest, which is exactly the shape this catches.
        int[] frequency = new int[alphabetSize];
        for (String code : codes) {
            for (int i = 0; i < code.length(); i++) {
                frequency[ShortCodeGenerator.ALPHABET.indexOf(code.charAt(i))]++;
            }
        }
        double expected = (double) codes.size() * ShortCodeGenerator.CODE_LENGTH / alphabetSize;
        int worst = 0;
        for (int i = 1; i < alphabetSize; i++) {
            if (Math.abs(frequency[i] - expected) > Math.abs(frequency[worst] - expected)) {
                worst = i;
            }
        }
        int worstCharacter = worst;
        assertAll(
                () -> assertTrue(
                        seenAtPosition.stream().allMatch(seen -> seen.size() == alphabetSize),
                        "every position must be able to hold every character; positions holding fewer: "
                                + seenAtPosition.stream().filter(s -> s.size() != alphabetSize).toList()),
                () -> assertTrue(
                        Math.abs(frequency[worstCharacter] - expected) < expected * 0.15,
                        "'" + ShortCodeGenerator.ALPHABET.charAt(worstCharacter) + "' appeared "
                                + frequency[worstCharacter] + " times against an expected " + expected
                                + "; a modulo of a random integer skews the alphabet like this"));
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
        // Two generators built at the same moment, in the same process, from the
        // same class: everything an attacker could observe about their creation is
        // identical, so anything they share afterwards came from a weak source.
        List<String> fromOne = sample(new ShortCodeGenerator(), 200);
        List<String> fromAnother = sample(new ShortCodeGenerator(), 200);

        Set<String> shared = new HashSet<>(fromOne);
        shared.retainAll(new HashSet<>(fromAnother));

        // And the randomness really comes from the injected source rather than
        // from a counter the constructor argument never touches.
        AtomicInteger drawsFromTheInjectedSource = new AtomicInteger();
        SecureRandom watched = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                drawsFromTheInjectedSource.incrementAndGet();
                super.nextBytes(bytes);
            }
        };
        List<String> fromTheWatchedSource = sample(new ShortCodeGenerator(watched), 50);

        assertAll(
                () -> assertTrue(shared.isEmpty(),
                        "two generators must not produce a shared code: " + shared),
                () -> assertTrue(drawsFromTheInjectedSource.get() > 0,
                        "the codes must be drawn from the CSPRNG the generator was given, not from "
                                + "a counter or a non-cryptographic source"),
                () -> assertEquals(
                        fromTheWatchedSource.size(),
                        new HashSet<>(fromTheWatchedSource).size(),
                        "and that source still yields distinct codes"));
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
        double bitsPerCharacter = Math.log(ShortCodeGenerator.ALPHABET.length()) / Math.log(2);
        double bitsCarried = bitsPerCharacter * ShortCodeGenerator.CODE_LENGTH;
        double bitsOneCharacterShorter = bitsPerCharacter * (ShortCodeGenerator.CODE_LENGTH - 1);
        Set<Character> alphabet = new HashSet<>();
        for (char c : ShortCodeGenerator.ALPHABET.toCharArray()) {
            alphabet.add(c);
        }

        assertAll(
                () -> assertTrue(
                        bitsCarried >= ShortCodeGenerator.ENTROPY_BITS,
                        ShortCodeGenerator.CODE_LENGTH + " characters carry " + bitsCarried
                                + " bits, short of the stated " + ShortCodeGenerator.ENTROPY_BITS),
                () -> assertTrue(
                        bitsOneCharacterShorter < ShortCodeGenerator.ENTROPY_BITS,
                        "the length is the minimum that carries the claim; anything longer is entropy "
                                + "nobody asked for"),
                () -> assertEquals(128, ShortCodeGenerator.ENTROPY_BITS,
                        "the entropy the contract publishes"),
                () -> assertEquals(
                        ShortCodeGenerator.ALPHABET.length(), alphabet.size(),
                        "a repeated character would make the alphabet smaller than it is claimed to be"));
    }

    // ---- helpers ----------------------------------------------------------

    /** Referenced so the frozen generator is on this class's compile path. */
    protected ShortCodeGenerator generator() {
        return new ShortCodeGenerator();
    }

    /** {@code count} codes from one generator, in the order it issued them. */
    private List<String> sample(ShortCodeGenerator generator, int count) {
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            codes.add(generator.generate());
        }
        return codes;
    }

    private boolean drawnFromTheAlphabet(String code) {
        return code.chars().allMatch(c -> ShortCodeGenerator.ALPHABET.indexOf(c) >= 0);
    }
}
