package com.example.urlshortener.unit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.link.HostNormalizer;
import com.example.urlshortener.support.Fixtures;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * What one canonical form for a host means (AC1, AC2), at the component that
 * decides it.
 *
 * <p>This is the one family of behaviour in this change that is examined below
 * HTTP, and for the same reason {@code PasswordStorageTest} is: the claim is
 * "resists equivalent-form evasion generally", not "resists these two examples",
 * and a claim about a general rule cannot be shown by the two URLs the
 * requirement happens to name. {@code HostNormalizer.normalize} is a frozen
 * static method with a worked table in its javadoc, so the table is what these
 * behaviours pin - including the rows that must <em>not</em> change, which is
 * where the dangerous failure lives.
 *
 * <p>Every expected value is quoted from {@code Fixtures} rather than written
 * here, so that the HTTP-level behaviours in {@code HostEvasionRefusalTest} and
 * these cannot disagree about what canonical means.
 *
 * <p>No Spring context: the method is pure, static and takes a string. Booting
 * an application to call it would add a minute to the suite and prove nothing
 * extra.
 */
class HostCanonicalisationTest {

    /**
     * A trailing dot is not part of a host: {@code malware.example.com.} and
     * {@code malware.example.com} canonicalise to the same value, and so do
     * {@code localhost.} and {@code localhost}. This is the mechanism behind AC1 -
     * the denylist row is matched because the two spellings have become one.
     *
     * <p>Demonstrates: AC1.
     */
    @Test
    void aTrailingDotIsNotPartOfAHost() {
        Optional<String> withoutTheDot = HostNormalizer.normalize("malware.example.com");
        Optional<String> withTheDot = HostNormalizer.normalize("malware.example.com.");
        Optional<String> withTwoDots = HostNormalizer.normalize("malware.example.com..");
        Optional<String> loopbackName = HostNormalizer.normalize("localhost.");

        assertAll(
                () -> assertEquals(Optional.of("malware.example.com"), withoutTheDot,
                        "the host as the denylist holds it is already canonical"),
                () -> assertEquals(withoutTheDot, withTheDot,
                        "a trailing dot is a spelling, not a different host - this is the AC1 fix"),
                () -> assertEquals(withoutTheDot, withTwoDots,
                        "every trailing dot is removed, not merely the last one"),
                () -> assertEquals(Optional.of("localhost"), loopbackName,
                        "the same rule applies to the name that resolves to loopback"));
    }

    /**
     * Case is not part of a host either: {@code MALWARE.Example.COM.} reaches the
     * same canonical value as the lower-case spelling. Lower-casing must be
     * locale-independent, so this holds on a JVM with a Turkish default locale
     * too, where {@code I} would otherwise become a dotless {@code ı} and the
     * denylist would miss.
     *
     * <p>Demonstrates: AC1.
     */
    @Test
    void caseIsNotPartOfAHost() {
        Optional<String> mixedCase = HostNormalizer.normalize("MALWARE.Example.COM.");
        Optional<String> onATurkishJvm;
        Locale beforeTheTest = Locale.getDefault();
        try {
            // The dotless i: on a Turkish JVM "PHISHING".toLowerCase() is
            // "phıshıng", which equals no denylist row anybody would write.
            Locale.setDefault(Locale.forLanguageTag("tr"));
            onATurkishJvm = HostNormalizer.normalize("PHISHING.EXAMPLE.NET");
        } finally {
            Locale.setDefault(beforeTheTest);
        }

        assertAll(
                () -> assertEquals(Optional.of("malware.example.com"), mixedCase,
                        "case and a trailing dot are one host written two ways"),
                () -> assertEquals(Optional.of("phishing.example.net"), onATurkishJvm,
                        "lower-casing must use Locale.ROOT, or the denylist misses on a Turkish JVM"));
    }

    /**
     * Every numeric spelling of the loopback address - one decimal number, one
     * hexadecimal number, one long octal number, the dotted-octal form and the
     * short two-part form - canonicalises to the dotted quad
     * {@code Fixtures.CANONICAL_LOOPBACK_HOST}, which is what makes AC2's
     * {@code http://2130706433/} the same host as {@code http://127.0.0.1/}.
     *
     * <p>Demonstrates: AC2.
     */
    @Test
    void everyNumericSpellingOfLoopbackCanonicalisesToOneDottedQuad() {
        assertAll(Fixtures.LOOPBACK_HOST_SPELLINGS.stream().map(spelling -> (Executable) () ->
                assertEquals(
                        Optional.of(Fixtures.CANONICAL_LOOPBACK_HOST),
                        HostNormalizer.normalize(spelling),
                        spelling + " is a spelling of " + Fixtures.CANONICAL_LOOPBACK_HOST
                                + " and a client would reach loopback through it")));
    }

    /**
     * A part with a leading zero is octal, as a browser and curl read it: the
     * first part of {@code 0177.0.0.1} is 127, not 177, and {@code 012.0.0.1} is
     * in private address space. This is the row where the obvious implementation
     * is not merely incomplete but wrong in the dangerous direction -
     * {@code InetAddress.getByName} canonicalises the first onto the public
     * address 177.0.0.1 - so it is pinned separately from the rest of the table.
     *
     * <p>Demonstrates: AC2.
     */
    @Test
    void aPartWithALeadingZeroIsReadAsOctalAsAClientWouldReadIt() {
        Optional<String> octalLoopback = HostNormalizer.normalize("0177.0.0.1");
        Optional<String> octalPrivate = HostNormalizer.normalize("012.0.0.1");

        assertAll(
                () -> assertEquals(Optional.of("127.0.0.1"), octalLoopback,
                        "0177 is octal 127, which is where a client actually connects"),
                () -> assertNotEquals(Optional.of("177.0.0.1"), octalLoopback,
                        "177.0.0.1 is a public address and is what InetAddress.getByName answers here"),
                () -> assertEquals(Optional.of("10.0.0.1"), octalPrivate,
                        "012 is octal 10, so this is private address space"),
                () -> assertNotEquals(Optional.of("12.0.0.1"), octalPrivate,
                        "reading the part as decimal would let a private address through"));
    }

    /**
     * A host that is numeric in every part but does not denote an address -
     * {@code 999.999.999.999}, {@code 4294967296} - cannot be canonicalised, and
     * the answer is empty rather than the input. Empty is a refusal the caller
     * must turn into 422: passing such a host through as a registered name is the
     * failure that would reopen the bypass under a different spelling.
     *
     * <p>Demonstrates: AC2, AC12.
     */
    @Test
    void aNumericHostThatIsOutOfRangeCannotBeCanonicalised() {
        Optional<String> everyPartOutOfRange = HostNormalizer.normalize("999.999.999.999");
        Optional<String> oneMoreThanThirtyTwoBits = HostNormalizer.normalize("4294967296");
        Optional<String> lastPartOutOfRange = HostNormalizer.normalize("127.0.65536");

        assertAll(
                () -> assertEquals(Optional.empty(), everyPartOutOfRange,
                        "an out-of-range candidate is refused, never passed through as a name"),
                () -> assertEquals(Optional.empty(), oneMoreThanThirtyTwoBits,
                        "4294967296 is one past the largest address there is"),
                () -> assertEquals(Optional.empty(), lastPartOutOfRange,
                        "the final part may only occupy the bytes the earlier parts left"),
                () -> assertNotEquals(Optional.of("999.999.999.999"), everyPartOutOfRange,
                        "returning the input would make the refusal look like acceptance"));
    }

    /**
     * A host containing an empty label - {@code a..b}, a bare dot - cannot be
     * canonicalised either. There is no reading of it that is unambiguous, so it
     * fails closed rather than being tidied into something that resolves.
     *
     * <p>Demonstrates: AC1, AC2.
     */
    @Test
    void aHostWithAnEmptyLabelCannotBeCanonicalised() {
        assertAll(
                () -> assertEquals(Optional.empty(), HostNormalizer.normalize("a..b"),
                        "an empty label in the middle has no unambiguous reading"),
                () -> assertEquals(Optional.empty(), HostNormalizer.normalize("."),
                        "a bare dot is nothing but a trailing dot, and what is left is empty"),
                () -> assertEquals(Optional.empty(), HostNormalizer.normalize(".."),
                        "and so is a pair of them"),
                () -> assertEquals(Optional.empty(), HostNormalizer.normalize(".example.com"),
                        "a leading dot is an empty first label, not a tidy-up"),
                () -> assertEquals(Optional.empty(), HostNormalizer.normalize(""),
                        "there is no canonical form of no host at all"));
    }

    /**
     * Normalisation never removes, merges or splits a label. {@code
     * notmalware.example.com} does not become {@code malware.example.com}, and
     * {@code malware.example.com.evil.test} does not either - the first is a
     * different name, the second is a different domain that merely begins with
     * one. Both must survive with every label intact.
     *
     * <p>This is the single most dangerous failure available here and the one no
     * acceptance criterion would catch: over-normalisation makes the filter look
     * strong while quietly refusing ordinary customer URLs, and every AC1/AC2
     * behaviour would still pass.
     *
     * <p>Demonstrates: AC1, AC2, AC17.
     */
    @Test
    void noLabelIsEverRemovedFromARegisteredName() {
        Optional<String> lookalike = HostNormalizer.normalize("notmalware.example.com");
        Optional<String> denylistedHostAsAPrefix =
                HostNormalizer.normalize("malware.example.com.evil.test");
        Optional<String> aSubdomainOfTheDenylistedHost =
                HostNormalizer.normalize("sub.campaign.malware.example.com");

        assertAll(
                () -> assertEquals(Optional.of("notmalware.example.com"), lookalike,
                        "a label is not a substring: notmalware is not malware"),
                () -> assertNotEquals(Optional.of("malware.example.com"), lookalike,
                        "collapsing this onto the denylisted parent would refuse an ordinary URL"),
                () -> assertEquals(Optional.of("malware.example.com.evil.test"), denylistedHostAsAPrefix,
                        "no trailing label is ever dropped, however suspicious the left of the name looks"),
                () -> assertEquals(Optional.of("sub.campaign.malware.example.com"),
                        aSubdomainOfTheDenylistedHost,
                        "nor is a leading one: matching the parent is the denylist's job, not the "
                                + "normaliser's"),
                () -> assertEquals(
                        5,
                        aSubdomainOfTheDenylistedHost.orElseThrow().split("\\.", -1).length,
                        "every one of the five labels survives: "
                                + aSubdomainOfTheDenylistedHost.orElse(null)));
    }

    /**
     * A host that is not an address candidate stays a registered name and is
     * returned as it stands: five numeric parts is not an address, and a part like
     * {@code 09} is not a number in any base this reads, so {@code 1.2.3.4.5} and
     * {@code 09.example.com} are names.
     *
     * <p>Demonstrates: AC2, AC17.
     */
    @Test
    void aHostThatIsNotAnAddressCandidateStaysARegisteredName() {
        assertAll(
                () -> assertEquals(Optional.of("1.2.3.4.5"), HostNormalizer.normalize("1.2.3.4.5"),
                        "five parts is not an address, so this is a name and survives intact"),
                () -> assertEquals(Optional.of("09.example.com"),
                        HostNormalizer.normalize("09.example.com"),
                        "09 has a leading zero and an 9 in it, so it is a number in no base read here"),
                () -> assertEquals(Optional.of("0x7g.example.com"),
                        HostNormalizer.normalize("0x7g.example.com"),
                        "0x7g is not hexadecimal either"),
                () -> assertEquals(Optional.of("example.com"), HostNormalizer.normalize("Example.COM"),
                        "an ordinary name is only lower-cased"));
    }

    /**
     * An IPv6 literal keeps its brackets and is lower-cased, and nothing else is
     * done to it - including the IPv4-mapped form {@code [::ffff:127.0.0.1]},
     * which is refused today and must stay refused.
     *
     * <p>Demonstrates: AC2.
     */
    @Test
    void anIpv6LiteralKeepsItsBracketsAndIsLowerCased() {
        Optional<String> ipv4Mapped = HostNormalizer.normalize("[::FFFF:127.0.0.1]");
        Optional<String> loopback = HostNormalizer.normalize("[::1]");
        Optional<String> documentation = HostNormalizer.normalize("[2001:DB8::1]");

        assertAll(
                () -> assertEquals(Optional.of("[::ffff:127.0.0.1]"), ipv4Mapped,
                        "brackets are kept and the literal is only lower-cased"),
                () -> assertEquals(Optional.of("[::1]"), loopback),
                () -> assertEquals(Optional.of("[2001:db8::1]"), documentation),
                () -> assertTrue(
                        ipv4Mapped.orElseThrow().startsWith("[") && ipv4Mapped.orElseThrow().endsWith("]"),
                        "an unbracketed literal is not the form java.net.URI hands over: " + ipv4Mapped));
    }

    /**
     * Canonicalising a canonical host changes nothing. A normaliser that is not
     * idempotent has two canonical forms, which is one more than "one canonical
     * form for a target host" allows, and the second one is reachable by a caller
     * who writes the first.
     *
     * <p>Demonstrates: AC1, AC2.
     */
    @Test
    void canonicalisingACanonicalHostChangesNothing() {
        List<String> everySpellingThisContractNames = java.util.stream.Stream.of(
                        Fixtures.LOOPBACK_HOST_SPELLINGS.stream(),
                        Fixtures.NAMES_PRESERVED_BY_NORMALISATION.stream(),
                        java.util.stream.Stream.of(
                                "MALWARE.Example.COM.", "localhost.", "[::FFFF:127.0.0.1]", "012.0.0.1"))
                .flatMap(s -> s)
                .toList();

        assertAll(everySpellingThisContractNames.stream().map(host -> (Executable) () -> {
            String canonical = HostNormalizer.normalize(host)
                    .orElseThrow(() -> new AssertionError(host + " should canonicalise, but did not"));
            assertEquals(Optional.of(canonical), HostNormalizer.normalize(canonical),
                    "normalising " + canonical + " again must not move it: there is one canonical form");
        }));
    }
}
