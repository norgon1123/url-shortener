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
