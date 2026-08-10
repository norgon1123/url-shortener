package com.example.urlshortener.link;

import java.net.URI;

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
 */
public class UrlValidator {

    /** Browser-compatible ceiling; also bounds the storage burn from junk links. */
    public static final int MAX_URL_LENGTH = 2048;

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
        throw new UnsupportedOperationException("Frozen contract skeleton; implemented by the implement node.");
    }

    /**
     * @throws com.example.urlshortener.error.ApiException {@code url_rejected}
     *         (422) if the host is internal or is this service itself
     */
    public void requireShortenable(URI url) {
        throw new UnsupportedOperationException("Frozen contract skeleton; implemented by the implement node.");
    }
}
