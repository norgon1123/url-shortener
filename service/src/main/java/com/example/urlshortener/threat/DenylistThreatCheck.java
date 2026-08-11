package com.example.urlshortener.threat;

import com.example.urlshortener.repository.ThreatDenylistRepository;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks a target against the denylist held in PostgreSQL.
 *
 * <p>A host matches if it is listed or is a subdomain of something listed, so one
 * row covers a campaign that keeps minting new subdomains.
 *
 * <p>The lookup runs against
 * {@link com.example.urlshortener.link.HostNormalizer#normalize(String)}'s
 * output rather than against the raw host, which is what closes the trailing-dot
 * evasion (AC1): {@code malware.example.com.} is walked as
 * {@code malware.example.com} and hits the seeded row. A host the normaliser
 * cannot canonicalise is treated as a match - this check fails closed too, even
 * though in practice {@code UrlValidator} has already refused it, because the
 * ordering of two checks is not something a security property should rest on.
 *
 * <p>The parent walk itself is unchanged and must stay label-based:
 * {@code sub.campaign.malware.example.com} is refused, while
 * {@code notmalware.example.com} and {@code malware.example.com.evil.test} are
 * not. Normalisation never removes a label - it lower-cases, strips trailing
 * dots, punycodes and canonicalises numeric addresses, and does nothing else -
 * so a public host can never be collapsed onto a denylisted parent.
 *
 * <p>No third-party reputation feed is called. None is named in the constraints,
 * and putting a paid external call on the create path is a procurement and
 * latency decision rather than an engineering one. If a feed is expected, it
 * implements this same port and nothing else in the design moves.
 */
public class DenylistThreatCheck implements ThreatCheck {

    private static final Logger log = LoggerFactory.getLogger(DenylistThreatCheck.class);

    private final ThreatDenylistRepository denylist;

    public DenylistThreatCheck(ThreatDenylistRepository denylist) {
        this.denylist = denylist;
    }

    @Override
    @Transactional(readOnly = true)
    public ThreatVerdict check(URI url) {
        String host = url.getHost();
        if (host == null) {
            return ThreatVerdict.ALLOW;
        }
        try {
            return isListed(host.toLowerCase(Locale.ROOT)) ? ThreatVerdict.BLOCK : ThreatVerdict.ALLOW;
        } catch (RuntimeException unavailable) {
            // Never propagates: an exception escaping into the create path would
            // turn a degraded dependency into a 500. The caller decides what an
            // unanswerable check means.
            log.warn("Threat denylist could not be consulted: {}", unavailable.getMessage());
            return ThreatVerdict.UNAVAILABLE;
        }
    }

    private boolean isListed(String host) {
        for (String candidate = host; candidate != null; candidate = parentOf(candidate)) {
            if (denylist.existsByHost(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** {@code a.b.example.com} -> {@code b.example.com} -> {@code example.com} -> null. */
    private static String parentOf(String host) {
        int dot = host.indexOf('.');
        return dot < 0 ? null : host.substring(dot + 1);
    }
}
