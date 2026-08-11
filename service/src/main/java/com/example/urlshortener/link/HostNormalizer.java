package com.example.urlshortener.link;

import java.net.IDN;
import java.util.Locale;
import java.util.Optional;

/**
 * One canonical form for a target host, shared by every host-based decision.
 *
 * <p>This type exists because the service had two host checks that each did
 * their own casual tidying, and each was evadable in a way the other was not:
 * {@link UrlValidator} matched only dotted-quad IPv4 literals, so
 * {@code http://2130706433/} never reached the internal-address test; and
 * {@link com.example.urlshortener.threat.DenylistThreatCheck} walked the raw
 * host's parents, so {@code malware.example.com.} never equalled the denylisted
 * row {@code malware.example.com}. Both are now decided on the output of this
 * one method, which is what makes "resists equivalent-form evasion generally"
 * something a reviewer can check in one place instead of two (A1).
 *
 * <h2>The contract</h2>
 *
 * <p>{@link #normalize(String)} answers one of two things: the canonical host,
 * or {@link Optional#empty()} meaning <em>this host cannot be canonicalised
 * unambiguously</em>. Empty is a refusal, not a pass-through: a caller must
 * translate it into 422 {@code url_rejected} and must never fall back to the
 * raw host (A3). Failing closed is the whole reason the method returns an
 * Optional rather than the input.
 *
 * <p>Normalisation is for <em>checking only</em>. The stored {@code long_url}
 * and the {@code Location} header stay byte-identical to what was submitted
 * (A2); nothing here ever rewrites a target.
 *
 * <h2>Steps, in order</h2>
 *
 * <ol>
 *   <li><b>IPv6 literal.</b> A host that starts with {@code [} and ends with
 *       {@code ]} (the form {@code java.net.URI#getHost()} returns) is
 *       lower-cased, brackets kept, and returned. No further step applies.
 *       Textual compression of the address is left to {@code InetAddress},
 *       which already unwraps and compares these correctly - including
 *       IPv4-mapped forms such as {@code [::ffff:127.0.0.1]}, which are refused
 *       today and must stay refused.</li>
 *   <li><b>Trailing dots.</b> Every trailing {@code .} is removed. A host of
 *       nothing but dots normalises to empty. This is the AC1 fix:
 *       {@code malware.example.com.} and {@code LOCALHOST.} become
 *       {@code malware.example.com} and {@code localhost}.</li>
 *   <li><b>Case.</b> Lower-cased with {@code Locale.ROOT}, never the default
 *       locale - the Turkish dotless i turns {@code I} into {@code ı} and would
 *       make the denylist miss on a Turkish JVM.</li>
 *   <li><b>IDN.</b> If any character is non-ASCII, {@code java.net.IDN.toASCII}
 *       is applied and the result lower-cased again; if it throws, the answer is
 *       empty. (In practice {@code java.net.URI} already returns a null host for
 *       a unicode authority, so these are refused at the syntax stage with 400
 *       and never reach here. The step is present so the guarantee does not
 *       depend on that.)</li>
 *   <li><b>Empty labels.</b> If the result still contains an empty label -
 *       {@code a..b}, or a leading dot - the answer is empty.</li>
 *   <li><b>Numeric IPv4.</b> See below. A host that is an IPv4 candidate is
 *       returned as a canonical dotted quad; one that is a candidate but does
 *       not parse is empty.</li>
 *   <li>Otherwise the host is a registered name and is returned as it stands
 *       after steps 2-5.</li>
 * </ol>
 *
 * <h2>Numeric IPv4</h2>
 *
 * <p>Split the host on {@code .}. It is an <b>IPv4 candidate</b> if there are
 * one to four parts and <em>every</em> part is a number:
 *
 * <ul>
 *   <li>{@code 0x} or {@code 0X} followed only by hex digits (an empty
 *       remainder is 0) - radix 16;</li>
 *   <li>otherwise a leading {@code 0} with length above 1 and only digits
 *       {@code 0-7} after it - radix 8;</li>
 *   <li>otherwise only decimal digits - radix 10.</li>
 * </ul>
 *
 * <p>A part that fails all three is not a number, and a host with any such part
 * is a registered name, not an address: {@code 09}, {@code 0x7g} and
 * {@code example} are all names, and {@code 1.2.3.4.5} is a name because five
 * parts is not an address.
 *
 * <p>For a candidate with {@code n} parts, the first {@code n-1} parts each
 * occupy one byte and must be below 256; the last part occupies the remaining
 * {@code 5-n} bytes and must be below {@code 256^(5-n)}. Anything out of range
 * is empty - {@code 999.999.999.999} and {@code 4294967296} are refused rather
 * than passed through as names. The 32-bit result is rendered {@code a.b.c.d}.
 *
 * <p>Worked cases, all of which must hold ({@code ->} reads "normalises to"):
 *
 * <pre>
 *   2130706433      -> 127.0.0.1     (AC2: decimal)
 *   0x7f000001      -> 127.0.0.1     (hex)
 *   017700000001    -> 127.0.0.1     (long octal)
 *   0177.0.0.1      -> 127.0.0.1     (dotted octal)
 *   127.1           -> 127.0.0.1     (short form)
 *   012.0.0.1       -> 10.0.0.1      (dotted octal, private)
 *   127.0.0.1       -> 127.0.0.1     (unchanged)
 *   10.0.0.1        -> 10.0.0.1      (unchanged)
 *   MALWARE.Example.COM. -> malware.example.com   (AC1)
 *   LOCALHOST.      -> localhost
 *   notmalware.example.com -> notmalware.example.com  (no label is ever dropped)
 *   999.999.999.999 -> empty         (candidate, out of range: 422)
 *   4294967296      -> empty         (candidate, out of range: 422)
 *   a..b            -> empty         (empty label: 422)
 *   1.2.3.4.5       -> 1.2.3.4.5     (five parts: a name)
 *   [::FFFF:127.0.0.1] -> [::ffff:127.0.0.1]
 * </pre>
 *
 * <h2>Why this is hand-rolled and not {@code InetAddress.getByName}</h2>
 *
 * <p>Because on JDK 21 that method is wrong for exactly the inputs this class
 * exists for, and wrong in the dangerous direction. Measured on
 * {@code openjdk 21.0.12}: {@code getByName("0177.0.0.1")} returns
 * <b>177.0.0.1</b>, a public address, while {@code curl} and glibc reach
 * 127.0.0.1; {@code getByName("0x7f000001")} and
 * {@code getByName("017700000001")} throw {@code UnknownHostException}. Built on
 * that method, the fix would look complete, the loopback bypasses would stay
 * open, and one form would be actively mis-canonicalised onto somebody else's
 * network. The threat model is what the client will actually connect to, so the
 * four numeric forms are parsed here by hand and only the resulting dotted quad
 * is handed to {@code InetAddress} - a form it parses deterministically.
 *
 * <h2>Where it is not applied</h2>
 *
 * <p>Not in {@link UrlValidator#parseOrThrow(String)}. Hosts that
 * {@code java.net.URI} cannot parse at all ({@code http://127.1/},
 * {@code http://0x7f.0.0.1/}, unicode authorities) are already refused today
 * with 400 {@code invalid_request}, and that split is pinned by the existing
 * suite. Moving host extraction ahead of the syntax gate to turn those 400s
 * into 422s is a separate, reviewable change to a documented status; neither
 * AC1 nor AC2 needs it, because both of their examples parse fine. This is
 * feasibility's U1, and the reading taken is "already refused is refused
 * enough".
 *
 * <p>No DNS is resolved, here or anywhere on the create path (Q10). A name that
 * resolves publicly at create time can resolve internally at click time, so the
 * lookup buys less than it appears to, and it would put a network round trip on
 * a write path the availability criteria want kept thin. The scope of
 * "equivalent form" is textual and numeric equivalence of what was written.
 */
public final class HostNormalizer {

    /** Five parts is a name, not an address, however numeric every part is. */
    private static final int MAX_ADDRESS_PARTS = 4;

    /** Not a radix, so it cannot be confused with one. */
    private static final int NOT_A_NUMBER = 0;

    private HostNormalizer() {
        // Pure function over its argument; there is no state worth an instance,
        // and a static call site cannot be wired to the wrong bean.
    }

    /**
     * @param host the host exactly as {@code java.net.URI#getHost()} returned it
     * @return the canonical host, or empty when it cannot be canonicalised
     *         unambiguously - which the caller must turn into 422
     *         {@code url_rejected}, never into acceptance and never into 400
     */
    public static Optional<String> normalize(String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        if (isIpv6Literal(host)) {
            return Optional.of(host.toLowerCase(Locale.ROOT));
        }

        String canonical = stripTrailingDots(host).toLowerCase(Locale.ROOT);
        if (canonical.isEmpty()) {
            return Optional.empty();
        }
        if (!isAscii(canonical)) {
            try {
                canonical = IDN.toASCII(canonical).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException notConvertible) {
                return Optional.empty();
            }
        }

        String[] labels = canonical.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty()) {
                return Optional.empty();
            }
        }
        return isAddressCandidate(labels) ? asDottedQuad(labels) : Optional.of(canonical);
    }

    private static boolean isIpv6Literal(String host) {
        return host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']';
    }

    private static String stripTrailingDots(String host) {
        int end = host.length();
        while (end > 0 && host.charAt(end - 1) == '.') {
            end--;
        }
        return host.substring(0, end);
    }

    private static boolean isAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** One to four parts, every one of them a number in some base a client reads. */
    private static boolean isAddressCandidate(String[] parts) {
        if (parts.length > MAX_ADDRESS_PARTS) {
            return false;
        }
        for (String part : parts) {
            if (radixOf(part) == NOT_A_NUMBER) {
                return false;
            }
        }
        return true;
    }

    /**
     * The base a part is written in, or {@link #NOT_A_NUMBER}.
     *
     * <p>A part with a leading zero is octal or nothing, which is what makes
     * {@code 09} a label rather than the decimal 9: that is how a client reads it,
     * and reading it as decimal here would canonicalise a name onto an address.
     */
    private static int radixOf(String part) {
        if (part.length() >= 2 && part.charAt(0) == '0' && (part.charAt(1) == 'x' || part.charAt(1) == 'X')) {
            return allDigitsIn(part, 2, 16) ? 16 : NOT_A_NUMBER;
        }
        if (part.length() > 1 && part.charAt(0) == '0') {
            return allDigitsIn(part, 1, 8) ? 8 : NOT_A_NUMBER;
        }
        return allDigitsIn(part, 0, 10) ? 10 : NOT_A_NUMBER;
    }

    private static boolean allDigitsIn(String part, int from, int radix) {
        if (from >= part.length()) {
            // Only reachable for "0x", whose empty remainder is zero.
            return radix == 16;
        }
        for (int index = from; index < part.length(); index++) {
            if (Character.digit(part.charAt(index), radix) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The dotted quad a candidate denotes, or empty when it is out of range.
     *
     * <p>Out of range is a refusal rather than a fallback to "it must have been a
     * name": {@code 4294967296} is not a registered name anybody can hold, and
     * passing it through would be the one case where an unparseable address
     * reached the internal-address test as a string it cannot recognise.
     */
    private static Optional<String> asDottedQuad(String[] parts) {
        long address = 0;
        for (int index = 0; index < parts.length - 1; index++) {
            long part = valueOf(parts[index]);
            if (part < 0 || part > 255) {
                return Optional.empty();
            }
            address |= part << (8 * (3 - index));
        }

        // The last part fills whatever bytes the earlier ones did not: 127.1 is
        // 127.0.0.1, not 127.1.0.0, because that is how a client expands it.
        long remainder = valueOf(parts[parts.length - 1]);
        if (remainder < 0 || remainder >= (1L << (8 * (5 - parts.length)))) {
            return Optional.empty();
        }
        address |= remainder;

        return Optional.of((address >>> 24) + "." + ((address >>> 16) & 0xFF)
                + "." + ((address >>> 8) & 0xFF) + "." + (address & 0xFF));
    }

    /** The part's value, or -1 when it does not fit a long and so cannot fit an address. */
    private static long valueOf(String part) {
        int radix = radixOf(part);
        String digits = switch (radix) {
            case 16 -> part.substring(2);
            case 8 -> part.substring(1);
            default -> part;
        };
        if (digits.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(digits, radix);
        } catch (NumberFormatException tooLarge) {
            return -1L;
        }
    }
}
