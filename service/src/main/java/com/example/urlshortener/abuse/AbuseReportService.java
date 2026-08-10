package com.example.urlshortener.abuse;

import com.example.urlshortener.auth.CurrentCustomer;
import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.domain.AbuseReportEntity;
import com.example.urlshortener.domain.LinkEntity;
import com.example.urlshortener.redirect.ResolutionCache;
import com.example.urlshortener.repository.AbuseReportRepository;
import com.example.urlshortener.repository.LinkRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an abuse report and takes the link down.
 *
 * <p>The takedown is immediate because there is no moderation console in this
 * build and therefore nobody for a queued report to wait for. A reviewer should
 * weigh that trade knowingly: this chooses "a signed-in customer can take down any
 * link they can name, subject to a rate limit and an audit row" over "abusive
 * links stay up until a human is hired". The audit row and the per-reporter bucket
 * are the whole of the defence against the feature being a cheap way to kill a
 * competitor\'s links.
 *
 * <p>A report against a code that resolves to nothing is still recorded and still
 * answered the same way. Refusing it would make this endpoint - the one that takes
 * a code from an untrusted caller - the existence oracle every other endpoint
 * avoids being.
 *
 * <p>Only the link is blocked; the target host is not added to the denylist. One
 * customer\'s report is evidence about one link, and promoting it to a rule about a
 * whole host would let a single report take down every other customer\'s links to
 * the same site.
 */
@Service
public class AbuseReportService {

    private static final Logger log = LoggerFactory.getLogger(AbuseReportService.class);

    private final LinkRepository links;
    private final AbuseReportRepository reports;
    private final ResolutionCache resolutionCache;
    private final String domain;

    public AbuseReportService(
            LinkRepository links,
            AbuseReportRepository reports,
            ResolutionCache resolutionCache,
            AppProperties properties) {
        this.links = links;
        this.reports = reports;
        this.resolutionCache = resolutionCache;
        this.domain = properties.domain();
    }

    @Transactional
    public void report(CurrentCustomer reporter, String code, String reason) {
        Instant now = Instant.now();
        Optional<LinkEntity> reported = links.findByDomainAndCode(domain, code);

        reports.save(new AbuseReportEntity(
                UUID.randomUUID(),
                domain,
                code,
                reported.map(LinkEntity::getId).orElse(null),
                reporter.id(),
                reason,
                now));

        reported.ifPresent(link -> {
            link.markBlocked();
            resolutionCache.invalidateAfterCommit(code);
        });

        log.info("Customer {} reported code {}; link present: {}", reporter.id(), code, reported.isPresent());
    }
}
