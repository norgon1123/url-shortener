package com.example.urlshortener.abuse;

import com.example.urlshortener.auth.CurrentCustomer;
import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.domain.AbuseReportEntity;
import com.example.urlshortener.domain.CustomerEntity;
import com.example.urlshortener.domain.LinkEntity;
import com.example.urlshortener.redirect.ResolutionCache;
import com.example.urlshortener.repository.AbuseReportRepository;
import com.example.urlshortener.repository.CustomerRepository;
import com.example.urlshortener.repository.LinkRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an abuse report and, if the reporter is entitled to it, takes the link
 * down.
 *
 * <p>The takedown is immediate because there is no moderation console in this
 * build and therefore nobody for a queued report to wait for. A reviewer should
 * weigh that trade knowingly: this chooses "a signed-in customer can take down any
 * link they can name, subject to a rate limit and an audit row" over "abusive
 * links stay up until a human is hired". The audit row and the per-reporter bucket
 * are the whole of the defence against the feature being a cheap way to kill a
 * competitor's links.
 *
 * <p><b>Which is why the reporter's age is now part of that defence.</b> The
 * per-reporter bucket bounded the attack only while reporter ids were provisioned
 * by hand; with self-service sign-up an id costs one unauthenticated request, so
 * 60 reports a minute per id is no bound at all. A report therefore blocks the
 * link only if the reporting account pre-dates it, or has existed for
 * {@code app.abuse.min-reporter-age} - so an account created to take down a link
 * already published cannot, and an ordinary customer reporting something minted
 * since they joined still can.
 *
 * <p><b>A report that does not qualify is recorded and answered exactly like any
 * other.</b> It has to be: this endpoint takes a code from an untrusted caller and
 * answers 202 whether or not the code resolves, so a visible refusal - of any
 * status, with any message - would tell the caller a link exists there, which is
 * the existence oracle every other endpoint avoids being and which the blanket 202
 * was built to prevent. The consequence is deliberate and worth stating plainly:
 * an unqualified reporter is told their report was accepted, and it was - it is on
 * file for the review process, it simply did not take anything down on its own
 * authority. That is the same thing that already happens to a report against a
 * code nobody ever issued.
 *
 * <p>Only the link is blocked; the target host is not added to the denylist. One
 * customer's report is evidence about one link, and promoting it to a rule about a
 * whole host would let a single report take down every other customer's links to
 * the same site.
 */
@Service
public class AbuseReportService {

    private static final Logger log = LoggerFactory.getLogger(AbuseReportService.class);

    private final LinkRepository links;
    private final AbuseReportRepository reports;
    private final CustomerRepository customers;
    private final ResolutionCache resolutionCache;
    private final String domain;
    private final Duration minReporterAge;

    public AbuseReportService(
            LinkRepository links,
            AbuseReportRepository reports,
            CustomerRepository customers,
            ResolutionCache resolutionCache,
            AppProperties properties) {
        this.links = links;
        this.reports = reports;
        this.customers = customers;
        this.resolutionCache = resolutionCache;
        this.domain = properties.domain();
        this.minReporterAge = properties.abuse().minReporterAge();
    }

    @Transactional
    public void report(CurrentCustomer reporter, String code, String reason) {
        Instant now = Instant.now();
        Optional<LinkEntity> reported = links.findByDomainAndCode(domain, code);
        // Filtered rather than branched, so the reporter is looked up only when
        // there is something to take down: a report against a code that resolves
        // to nothing costs the one query it always did.
        Optional<LinkEntity> takenDown = reported.filter(link -> mayTakeDown(reporter, link, now));

        reports.save(new AbuseReportEntity(
                UUID.randomUUID(),
                domain,
                code,
                reported.map(LinkEntity::getId).orElse(null),
                reporter.id(),
                reason,
                now));

        takenDown.ifPresent(link -> {
            link.markBlocked();
            resolutionCache.invalidateAfterCommit(code);
        });

        log.info(
                "Customer {} reported code {}; link present: {}; blocked: {}",
                reporter.id(),
                code,
                reported.isPresent(),
                takenDown.isPresent());
    }

    /**
     * Whether this reporter may act on this link on their own authority: an
     * account that already existed when the link was created, or one that has
     * existed for {@code app.abuse.min-reporter-age}, may; a newer one may not.
     *
     * <p>An account that cannot be read fails closed. The token verified, so the
     * row should be there, and if it is not then nothing is known about how long
     * it has existed - which is not a basis for a permanent takedown.
     */
    private boolean mayTakeDown(CurrentCustomer reporter, LinkEntity link, Instant now) {
        Instant reporterSince = customers.findById(reporter.id())
                .map(CustomerEntity::getCreatedAt)
                .orElse(now);

        return !reporterSince.isAfter(link.getCreatedAt())
                || !reporterSince.isAfter(now.minus(minReporterAge));
    }
}
