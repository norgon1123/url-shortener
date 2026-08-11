package com.example.urlshortener.link;

import com.example.urlshortener.error.ApiException;
import com.example.urlshortener.error.ErrorCode;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What may be turned into a short link (A14).
 *
 * <p>Two stages with two different statuses, and the split is the point:
 *
 * <ul>
 *   <li>{@link #parseOrThrow(String)} is syntax - absolute, {@code http} or
 *       {@code https}, with a host, no longer than {@link #MAX_URL_LENGTH}. A
 *       caller can fix these, so they answer 400 and say which field was wrong.</li>
 *   <li>{@link #requireShortenable(URI)} is policy - loopback, private,
 *       link-local, unique-local or this service's own host. These answer 422
 *       with a single fixed message, deliberately identical to the message a
 *       denylisted target gets, so that the response cannot be used to map
 *       either our internal network or the contents of the denylist.</li>
 * </ul>
 *
 * <p>Hosts are checked as written - literal IPv4/IPv6 forms and the well-known
 * internal names - and DNS is not resolved. Two reasons: a name that resolves
 * publicly at create time can resolve internally at click time, so a lookup buys
 * less than it appears to; and a DNS round trip on the create path is one more
 * dependency that can be slow or down, which AC20 asks us to keep off the write
 * path where we can.
 *
 * <p><b>"As written" is not the same as "as typed".</b> {@link #requireShortenable(URI)}
 * decides on the output of {@link HostNormalizer#normalize(String)} and on
 * nothing else, so {@code LOCALHOST.} and {@code http://2130706433/} reach the
 * internal-address test as {@code localhost} and {@code 127.0.0.1}. A host the
 * normaliser cannot canonicalise is refused here with 422 rather than accepted
 * or downgraded to 400 (A3) - this check fails closed. The service's own host is
 * compared in the same canonical form, so the self-referential rule cannot be
 * evaded by a trailing dot either.
 *
 * <p>{@link #parseOrThrow(String)} is deliberately <em>not</em> normalised. The
 * split above is a documented part of the contract and the existing suite pins
 * it: forms {@code java.net.URI} cannot parse a host from stay 400, because they
 * are already refused and moving them to 422 changes a documented status for no
 * acceptance criterion. See {@link HostNormalizer} for the full reasoning and
 * for why the numeric IPv4 parsing is hand-rolled rather than delegated to
 * {@code InetAddress.getByName}.
 */
public class UrlValidator {

    /** Browser-compatible ceiling; also bounds the storage burn from junk links. */
    public static final int MAX_URL_LENGTH = 2048;

    private static final String LOCALHOST = "localhost";

    /** Names that resolve inside a network rather than on the internet. */
    private static final Set<String> INTERNAL_SUFFIXES = Set.of("local", "internal", "localdomain", "home.arpa");

    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final String ownHost;

    /** For unit use: every rule except the "our own domain" check. */
    public UrlValidator() {
        this(null);
    }

    /**
     * @param ownHost this shortener's public host, refused as a target so a short
     *                link cannot point at another short link and build a redirect
     *                loop through us
     */
    public UrlValidator(String ownHost) {
        this.ownHost = ownHost;
    }

    /** The host refused as self-referential, or null when unset. */
    protected String ownHost() {
        return ownHost;
    }

    /**
     * @return the parsed URL
     * @throws com.example.urlshortener.error.ApiException {@code invalid_request}
     *         (400) if it is not an absolute http/https URL with a host, or is
     *         longer than {@link #MAX_URL_LENGTH}
     */
    public URI parseOrThrow(String longUrl) {
        if (longUrl == null || longUrl.isBlank()) {
            throw invalidUrl("must not be blank");
        }
        if (longUrl.length() > MAX_URL_LENGTH) {
            throw invalidUrl("must be at most " + MAX_URL_LENGTH + " characters");
        }

        URI url;
        try {
            url = new URI(longUrl);
        } catch (URISyntaxException notAUrl) {
            throw invalidUrl("must be an absolute http or https URL");
        }

        String scheme = url.getScheme();
        boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!url.isAbsolute() || !httpScheme || url.getHost() == null || url.getHost().isBlank()) {
            throw invalidUrl("must be an absolute http or https URL");
        }
        return url;
    }

    /**
     * @throws com.example.urlshortener.error.ApiException {@code url_rejected}
     *         (422) if the host is internal or is this service itself
     */
    public void requireShortenable(URI url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (ownHost != null && host.equals(ownHost.toLowerCase(Locale.ROOT))) {
            throw ApiException.urlRejected();
        }
        if (isInternal(host)) {
            throw ApiException.urlRejected();
        }
    }

    private static boolean isInternal(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;

        if (bare.equals(LOCALHOST) || bare.endsWith("." + LOCALHOST)) {
            return true;
        }
        for (String suffix : INTERNAL_SUFFIXES) {
            if (bare.equals(suffix) || bare.endsWith("." + suffix)) {
                return true;
            }
        }
        if (!isIpLiteral(host)) {
            // A name we do not recognise is left alone rather than resolved. A name
            // that resolves publicly now can resolve internally at click time, so
            // the lookup buys less than it appears to, and it would put a DNS round
            // trip on the create path for the privilege.
            return false;
        }
        try {
            return isInternalAddress(InetAddress.getByName(bare));
        } catch (UnknownHostException notAnAddress) {
            return false;
        }
    }

    private static boolean isInternalAddress(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        // IPv6 unique local addresses, fc00::/7: private space with no equivalent
        // isXxx() predicate on InetAddress.
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static boolean isIpLiteral(String host) {
        return IPV4_LITERAL.matcher(host).matches() || host.startsWith("[") || host.indexOf(':') >= 0;
    }

    private static ApiException invalidUrl(String detail) {
        return ApiException.invalidRequest(ErrorCode.INVALID_REQUEST.defaultMessage(), Map.of("longUrl", detail));
    }
}
