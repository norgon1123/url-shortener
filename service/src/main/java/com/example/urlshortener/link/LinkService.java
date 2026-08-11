package com.example.urlshortener.link;

import com.example.urlshortener.api.AnonymousLinkResponse;
import com.example.urlshortener.api.CreateAnonymousLinkRequest;
import com.example.urlshortener.api.CreateLinkRequest;
import com.example.urlshortener.api.LinkPage;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.auth.CurrentCustomer;
import com.example.urlshortener.click.ClickCounter;
import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.domain.LinkEntity;
import com.example.urlshortener.domain.LinkStatus;
import com.example.urlshortener.error.ApiException;
import com.example.urlshortener.error.ErrorCode;
import com.example.urlshortener.redirect.ResolutionCache;
import com.example.urlshortener.repository.LinkRepository;
import com.example.urlshortener.threat.ThreatCheck;
import com.example.urlshortener.threat.ThreatVerdict;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything a customer can do to their own links.
 *
 * <p>The transaction boundary is here rather than in the controller, and so is
 * every ownership decision. Owner scoping happens in the query -- a link that
 * belongs to somebody else is not filtered out of the result, it is never in it --
 * so "not yours" and "never existed" come out the same without anybody having to
 * remember to make them so.
 */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    private final LinkRepository links;
    private final ShortCodeGenerator shortCodes;
    private final AliasPolicy aliasPolicy;
    private final UrlValidator urlValidator;
    private final ThreatCheck threatCheck;
    private final ClickCounter clickCounter;
    private final ResolutionCache resolutionCache;

    private final String baseUrl;
    private final String domain;
    private final Duration defaultTtl;
    private final Duration anonymousTtl;
    private final boolean threatFailOpen;

    public LinkService(
            LinkRepository links,
            ShortCodeGenerator shortCodes,
            AliasPolicy aliasPolicy,
            UrlValidator urlValidator,
            ThreatCheck threatCheck,
            ClickCounter clickCounter,
            ResolutionCache resolutionCache,
            AppProperties properties) {
        this.links = links;
        this.shortCodes = shortCodes;
        this.aliasPolicy = aliasPolicy;
        this.urlValidator = urlValidator;
        this.threatCheck = threatCheck;
        this.clickCounter = clickCounter;
        this.resolutionCache = resolutionCache;
        this.baseUrl = properties.baseUrl();
        this.domain = properties.domain();
        this.defaultTtl = properties.links().defaultTtl();
        this.anonymousTtl = properties.links().anonymousTtl();
        this.threatFailOpen = properties.threat().failOpen();
    }

    @Transactional
    public LinkResponse create(CurrentCustomer caller, CreateLinkRequest request) {
        Instant now = Instant.now();

        if (request.alias() != null) {
            aliasPolicy.requireAcceptable(request.alias());
        }
        Instant expiresAt = expiryFor(request.expiresAt(), now);

        URI target = urlValidator.parseOrThrow(request.longUrl());
        urlValidator.requireShortenable(target);
        requirePermittedTarget(target);

        LinkEntity link = request.alias() == null
                ? insertWithGeneratedCode(caller.id(), request.longUrl(), now, expiresAt)
                : insertWithAlias(caller.id(), request.alias(), request.longUrl(), now, expiresAt);

        // Clears any "no such code" a probe of this code left behind. It is the one
        // cache operation that refuses the request when it cannot be done: see
        // ResolutionCache for why issuing a code we cannot clear is worse than not
        // issuing one at all.
        resolutionCache.invalidateBeforeIssuing(link.getCode());

        log.info("Customer {} created link {}", caller.id(), link.getCode());
        return toResponse(link, now);
    }

    /**
     * Creates a link nobody owns (AC9).
     *
     * <p>The same sequence as {@link #create}, in the same order, through the same
     * validator, threat check, generator and pre-issue cache invalidation: an
     * anonymous link differs from an owned one in the owner it stores and the
     * expiry it is given, and in nothing else. Written as a second method rather
     * than a nullable-caller branch through the first because the two differ in
     * their request and response types, but they must never differ in what they
     * accept - there is no target this path takes that {@link #create} refuses
     * (AC12).
     *
     * <p>The expiry is {@code app.links.anonymous-ttl} from creation and is never
     * caller-supplied: nobody owns the row, so nobody could shorten it afterwards
     * if the creator chose it (A9).
     */
    @Transactional
    public AnonymousLinkResponse createAnonymous(CreateAnonymousLinkRequest request) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(anonymousTtl);

        URI target = urlValidator.parseOrThrow(request.longUrl());
        urlValidator.requireShortenable(target);
        requirePermittedTarget(target);

        LinkEntity link = insertWithGeneratedCode(null, request.longUrl(), now, expiresAt);
        resolutionCache.invalidateBeforeIssuing(link.getCode());

        // No customer to attribute it to, and none is invented for the log line:
        // the absence is the fact worth recording.
        log.info("Created anonymous link {}", link.getCode());
        return new AnonymousLinkResponse(
                link.getCode(),
                baseUrl + "/" + link.getCode(),
                link.getLongUrl(),
                link.getCreatedAt(),
                link.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public LinkResponse get(CurrentCustomer caller, String code) {
        return toResponse(ownedOrNotFound(caller, code), Instant.now());
    }

    @Transactional(readOnly = true)
    public LinkPage list(CurrentCustomer caller, int page, int size) {
        Instant now = Instant.now();
        Page<LinkEntity> owned = links.findOwnedBy(caller.id(), PageRequest.of(page, size));
        List<LinkResponse> items = owned.getContent().stream().map(link -> toResponse(link, now)).toList();
        return new LinkPage(items, page, size, owned.getTotalElements(), owned.getTotalPages());
    }

    @Transactional
    public LinkResponse updateExpiry(CurrentCustomer caller, String code, Instant expiresAt) {
        Instant now = Instant.now();
        requireFuture(expiresAt, now);

        LinkEntity link = ownedOrNotFound(caller, code);
        if (link.getStatus() != LinkStatus.ACTIVE) {
            // 409 rather than 404: the caller does own this link. A link taken down
            // for abuse must not be revivable by its owner pushing the expiry out.
            throw ApiException.linkNotModifiable();
        }

        link.setExpiresAt(expiresAt);
        resolutionCache.invalidateAfterCommit(code);
        log.info("Customer {} changed the expiry of link {}", caller.id(), code);
        return toResponse(link, now);
    }

    @Transactional
    public void delete(CurrentCustomer caller, String code) {
        LinkEntity link = ownedOrNotFound(caller, code);
        link.markDeleted();
        resolutionCache.invalidateAfterCommit(code);
        log.info("Customer {} deleted link {}", caller.id(), code);
    }

    /** The only lookup a management endpoint makes: owned by this caller, or nothing. */
    private LinkEntity ownedOrNotFound(CurrentCustomer caller, String code) {
        return links.findByDomainAndCodeAndCustomerId(domain, code, caller.id())
                .orElseThrow(ApiException::notFound);
    }

    private LinkEntity insertWithAlias(
            UUID owner, String alias, String longUrl, Instant now, Instant expiresAt) {
        if (links.existsByDomainAndCode(domain, alias)) {
            throw ApiException.aliasUnavailable();
        }
        try {
            return links.saveAndFlush(newLink(owner, alias, longUrl, now, expiresAt));
        } catch (DataIntegrityViolationException takenMeanwhile) {
            // The unique constraint, not the check above, is what actually decides:
            // two callers can pass that check at the same moment and only one row
            // can exist.
            throw ApiException.aliasUnavailable();
        }
    }

    private LinkEntity insertWithGeneratedCode(
            UUID owner, String longUrl, Instant now, Instant expiresAt) {
        // At 128 bits a collision is not something that happens; the loop is here so
        // that if one ever did, a customer would get a link rather than an error.
        // The unique constraint stays the authority - this check only picks a code
        // that is free, it does not make the insert safe.
        for (int attempt = 1; attempt <= ShortCodeGenerator.MAX_INSERT_ATTEMPTS; attempt++) {
            String code = shortCodes.generate();
            if (links.existsByDomainAndCode(domain, code)) {
                log.warn("Generated short code was already taken on attempt {}", attempt);
                continue;
            }
            return links.saveAndFlush(newLink(owner, code, longUrl, now, expiresAt));
        }
        log.error("Could not find a free short code in {} attempts", ShortCodeGenerator.MAX_INSERT_ATTEMPTS);
        throw ApiException.dependencyUnavailable(ErrorCode.SERVICE_UNAVAILABLE.defaultMessage());
    }

    /** @param owner the customer the link belongs to, or null when it belongs to nobody. */
    private LinkEntity newLink(
            UUID owner, String code, String longUrl, Instant now, Instant expiresAt) {
        return new LinkEntity(UUID.randomUUID(), domain, code, owner, longUrl, now, expiresAt);
    }

    private void requirePermittedTarget(URI target) {
        ThreatVerdict verdict = threatCheck.check(target);
        if (verdict == ThreatVerdict.BLOCK) {
            log.info("Refused a link to denylisted host {}", target.getHost());
            throw ApiException.urlRejected();
        }
        if (verdict == ThreatVerdict.UNAVAILABLE) {
            if (!threatFailOpen) {
                throw ApiException.dependencyUnavailable(ErrorCode.SERVICE_UNAVAILABLE.defaultMessage());
            }
            // Logged every time, so a fail-open decision is auditable rather than
            // assumed. Collapsing it into "clean" is how it becomes invisible.
            log.warn("Accepting a link to {} without a threat verdict", target.getHost());
        }
    }

    private Instant expiryFor(Instant requested, Instant now) {
        if (requested == null) {
            return now.plus(defaultTtl);
        }
        requireFuture(requested, now);
        return requested;
    }

    private static void requireFuture(Instant expiresAt, Instant now) {
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw ApiException.invalidRequest(
                    ErrorCode.INVALID_REQUEST.defaultMessage(), Map.of("expiresAt", "must be in the future"));
        }
    }

    /**
     * The durable total is read first and the un-flushed delta second, which is the
     * order that keeps the two-tier count agreeing with itself: the flush writes the
     * durable total and then removes the delta, so reading in the same direction
     * leaves only the flush own round trip as a window instead of the whole gap
     * between our two reads.
     */
    private LinkResponse toResponse(LinkEntity link, Instant now) {
        long durable = link.getClickCount();
        long pending = clickCounter.pendingDelta(link.getId());
        return new LinkResponse(
                link.getCode(),
                baseUrl + "/" + link.getCode(),
                link.getLongUrl(),
                link.statusAt(now),
                link.getCreatedAt(),
                link.getExpiresAt(),
                durable + pending);
    }
}
