package com.example.urlshortener.link;

import java.security.SecureRandom;

/**
 * Generates short codes that cannot be guessed or derived from each other
 * (AC16).
 *
 * <p>Every character is drawn uniformly from a 62-character alphabet using a
 * CSPRNG. Nothing about a code depends on the row id, the creation time, the
 * long URL, the customer, or any previously issued code, so holding a large set
 * of issued codes tells an attacker nothing about the next one - which is the
 * literal wording of AC16.
 *
 * <p><strong>On the length.</strong> A2 says "128 bits of CSPRNG output rendered
 * as ~11 base62 characters", and those two halves disagree: 11 base62 characters
 * carry log2(62)*11 &asymp; 65 bits, not 128. The bit strength is the load-bearing
 * half - AC16 is an acceptance criterion and "short" is not - so this contract
 * keeps 128 bits and corrects the character count to {@value #CODE_LENGTH}
 * ({@code ceil(128 / log2(62))}), which carries ~131 bits. The cost is honest and
 * belongs in front of the reviewer: a 22-character code makes a short link about
 * 40 characters long, which is longer than the products this is modelled on.
 * Those products buy their 7-character codes with aggressive enumeration
 * defence; we have that too (the tight 404 bucket), but the requirement asks for
 * unguessability as an acceptance criterion, so we do not spend the entropy. It
 * is one constant, and trading it against the rate limit later is a one-line
 * change plus a migration-free rollout, because old codes stay valid.
 *
 * <p>Uniqueness is enforced by the {@code (domain, code)} unique constraint, not
 * by a pre-insert existence check: at the target write rate a check-then-insert
 * is a race, and the constraint is the only thing that is actually atomic. A
 * duplicate is retried with a fresh code.
 */
public class ShortCodeGenerator {

    /** Base62, digits first: URL-safe with no escaping and no separators. */
    public static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** Minimum entropy per generated code. */
    public static final int ENTROPY_BITS = 128;

    /** Characters per generated code: {@code ceil(128 / log2(62))}. */
    public static final int CODE_LENGTH = 22;

    /** Attempts before a create fails, if the unique constraint keeps rejecting. */
    public static final int MAX_INSERT_ATTEMPTS = 5;

    private final SecureRandom random;

    public ShortCodeGenerator() {
        this(new SecureRandom());
    }

    public ShortCodeGenerator(SecureRandom random) {
        this.random = random;
    }

    /** The CSPRNG in use; exposed so a test can seed a deterministic one. */
    protected SecureRandom random() {
        return random;
    }

    /**
     * @return a fresh code of exactly {@link #CODE_LENGTH} characters, each drawn
     *         uniformly from {@link #ALPHABET}. Uniform means uniform: taking
     *         {@code nextInt() % 62} skews the first four characters of the
     *         alphabet and quietly costs entropy, so the draw must be unbiased.
     */
    public String generate() {
        char[] code = new char[CODE_LENGTH];
        for (int position = 0; position < CODE_LENGTH; position++) {
            // SecureRandom.nextInt(bound) rejects the values that would skew a
            // modulo, so every character is drawn uniformly. `nextInt() % 62` would
            // favour the first four characters of the alphabet and quietly cost
            // part of the entropy this length was chosen to carry.
            code[position] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(code);
    }
}
