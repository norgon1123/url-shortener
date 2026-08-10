package com.example.urlshortener.link;

import com.example.urlshortener.api.CreateLinkRequest;
import com.example.urlshortener.api.LinkPage;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.api.UpdateLinkExpiryRequest;
import com.example.urlshortener.auth.CurrentCustomer;
import com.example.urlshortener.error.ApiException;
import com.example.urlshortener.error.ErrorCode;
import com.example.urlshortener.ratelimit.RateLimitBucket;
import com.example.urlshortener.ratelimit.RateLimitGuard;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-scoped link management.
 *
 * <p>Every path is spelled out in full on the method rather than assembled from a
 * class-level prefix. It is more to read, and it means the route in the source is
 * the route on the wire, which is what both a reviewer and the mechanical route
 * check are comparing against the specification.
 *
 * <p>The write bucket is charged here, on the three methods that create storage or
 * change state, and not on the two that read. Clicks are charged to a different
 * bucket entirely, so exhausting this one cannot stop a link being served.
 */
@RestController
public class LinkController {

    /** Above this, one request could ask for an unbounded amount of work. */
    private static final int MAX_PAGE_SIZE = 100;

    private final LinkService linkService;
    private final RateLimitGuard rateLimitGuard;

    public LinkController(LinkService linkService, RateLimitGuard rateLimitGuard) {
        this.linkService = linkService;
        this.rateLimitGuard = rateLimitGuard;
    }

    /**
     * {@code Location} points at the API resource for the new link rather than at
     * the short URL: a client pastes the short URL, it does not follow it, and the
     * short URL is in the body for that.
     */
    @PostMapping("/api/v1/links")
    public ResponseEntity<LinkResponse> create(
            CurrentCustomer caller, @Valid @RequestBody CreateLinkRequest request) {

        rateLimitGuard.requireByCustomer(RateLimitBucket.WRITE, caller.id());
        LinkResponse created = linkService.create(caller, request);
        return ResponseEntity.created(URI.create("/api/v1/links/" + created.code())).body(created);
    }

    @GetMapping("/api/v1/links")
    public LinkPage list(
            CurrentCustomer caller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        requireValidPaging(page, size);
        return linkService.list(caller, page, size);
    }

    @GetMapping("/api/v1/links/{code}")
    public LinkResponse get(CurrentCustomer caller, @PathVariable String code) {
        return linkService.get(caller, code);
    }

    @PatchMapping("/api/v1/links/{code}")
    public LinkResponse updateExpiry(
            CurrentCustomer caller,
            @PathVariable String code,
            @Valid @RequestBody UpdateLinkExpiryRequest request) {

        rateLimitGuard.requireByCustomer(RateLimitBucket.WRITE, caller.id());
        return linkService.updateExpiry(caller, code, request.expiresAt());
    }

    /**
     * No body, deliberately: there is nothing to say, and a body describing what
     * was deleted would be a way to confirm what existed.
     */
    @DeleteMapping("/api/v1/links/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(CurrentCustomer caller, @PathVariable String code) {
        rateLimitGuard.requireByCustomer(RateLimitBucket.WRITE, caller.id());
        linkService.delete(caller, code);
    }

    /**
     * Refused rather than clamped. Silently turning {@code size=1000} into
     * {@code size=100} means a client that believes it has walked the whole
     * collection has not, and it will never find out.
     */
    private static void requireValidPaging(int page, int size) {
        if (page < 0) {
            throw ApiException.invalidRequest(
                    ErrorCode.INVALID_REQUEST.defaultMessage(), Map.of("page", "must not be negative"));
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.invalidRequest(
                    ErrorCode.INVALID_REQUEST.defaultMessage(),
                    Map.of("size", "must be between 1 and " + MAX_PAGE_SIZE));
        }
    }
}
