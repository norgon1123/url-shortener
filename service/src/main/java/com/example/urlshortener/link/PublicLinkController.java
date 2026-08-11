package com.example.urlshortener.link;

import com.example.urlshortener.api.AnonymousLinkResponse;
import com.example.urlshortener.api.CreateAnonymousLinkRequest;
import com.example.urlshortener.ratelimit.RateLimitBucket;
import com.example.urlshortener.ratelimit.RateLimitGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Link creation for a caller with no account (AC9).
 *
 * <p>Separate from {@link LinkController} rather than a second method on it,
 * because the two differ in everything the class-level statements are about: this
 * one takes no {@link com.example.urlshortener.auth.CurrentCustomer} - declaring
 * one would 401 the request from the argument resolver on a path that has
 * deliberately been exempted from the session filter - and it charges a bucket
 * keyed by client address rather than by customer id.
 *
 * <p>201 with no {@code Location} header. The only resource path it could name is
 * {@code /api/v1/links/{code}}, which answers 404 for this code for every caller
 * including the one that just created it, and a header that resolves for nobody
 * is still a promise that costs a breaking change to withdraw.
 */
@RestController
public class PublicLinkController {

    private final LinkService linkService;
    private final RateLimitGuard rateLimitGuard;

    public PublicLinkController(LinkService linkService, RateLimitGuard rateLimitGuard) {
        this.linkService = linkService;
        this.rateLimitGuard = rateLimitGuard;
    }

    /**
     * The bucket is charged before anything is stored, and it is keyed by
     * {@code getRemoteAddr()} because an unauthenticated caller has no identity to
     * key on. {@code X-Forwarded-For} is deliberately not consulted: trusting a
     * client-supplied header with no trusted-proxy list would make every IP-keyed
     * bucket in this service spoofable, including the ones defending the click
     * path.
     */
    @PostMapping("/api/v1/public/links")
    @ResponseStatus(HttpStatus.CREATED)
    public AnonymousLinkResponse create(
            @Valid @RequestBody CreateAnonymousLinkRequest request, HttpServletRequest httpRequest) {

        rateLimitGuard.requireByAddress(RateLimitBucket.ANONYMOUS_CREATE, httpRequest.getRemoteAddr());
        return linkService.createAnonymous(request);
    }
}
