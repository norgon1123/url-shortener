package com.example.urlshortener.redirect;

import com.example.urlshortener.api.ApiError;
import com.example.urlshortener.click.ClickCounter;
import com.example.urlshortener.error.ErrorCode;
import com.example.urlshortener.ratelimit.RateLimitBucket;
import com.example.urlshortener.ratelimit.RateLimitDecision;
import com.example.urlshortener.ratelimit.RateLimitGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public redirect, at the root of the namespace because that is what makes a
 * short link short.
 *
 * <p>This route takes no session and never has: whoever clicks is not our
 * customer and will never sign in, so it sits outside the session filter URL
 * mappings entirely rather than inside them with an exception carved out.
 *
 * <p>HEAD is served by this same handler -- Spring MVC dispatches it to the GET
 * mapping -- with the same status and headers and no body. It deliberately has no
 * mapping of its own.
 *
 * <p>Everything here is arranged so this route cannot answer 5xx. It builds each
 * of its own responses instead of throwing to the exception handler, which is
 * what keeps the cache headers identical on a redirect, on a not-found and on a
 * refusal alike; and it catches whatever escapes, because a clicker has no way to
 * act on a stack trace and the contract offers no status that means we failed.
 */
@RestController
public class RedirectController {

    private static final Logger log = LoggerFactory.getLogger(RedirectController.class);

    /**
     * Not decoration. Without these a browser or an intermediary serves the second
     * and every later click out of its own cache: the click never reaches us, the
     * count silently stops growing, and a link taken down keeps redirecting long
     * after the published bound has passed.
     */
    private static final String NO_STORE = "no-store, no-cache, must-revalidate, max-age=0";

    private final LinkResolver resolver;
    private final ClickCounter clickCounter;
    private final RateLimitGuard rateLimitGuard;

    public RedirectController(LinkResolver resolver, ClickCounter clickCounter, RateLimitGuard rateLimitGuard) {
        this.resolver = resolver;
        this.clickCounter = clickCounter;
        this.rateLimitGuard = rateLimitGuard;
    }

    @GetMapping("/{code}")
    public ResponseEntity<Object> follow(@PathVariable String code, HttpServletRequest request) {
        String clientAddress = request.getRemoteAddr();
        try {
            RateLimitDecision clicks = rateLimitGuard.consume(RateLimitBucket.CLICK, clientAddress);
            if (!clicks.allowed()) {
                return throttled(clicks);
            }

            CachedLink link = resolver.resolve(code);
            if (link.redirectsAt(Instant.now())) {
                clickCounter.record(link.id());
                return redirectTo(link.longUrl());
            }

            // Charged only to requests that did not resolve. An enumeration sweep is
            // a long run of 404s and a link that has gone viral is a long run of
            // 302s; sharing one bucket would mean throttling the second in order to
            // stop the first.
            RateLimitDecision sweep = rateLimitGuard.consume(RateLimitBucket.NOT_FOUND, clientAddress);
            if (!sweep.allowed()) {
                return throttled(sweep);
            }
            return notFound();
        } catch (RuntimeException unexpected) {
            log.error("Click on {} could not be served; answering not-found rather than a server error",
                    code, unexpected);
            return notFound();
        }
    }

    private static ResponseEntity<Object> redirectTo(String longUrl) {
        // Set verbatim rather than through location(URI), which would normalise the
        // target. What was submitted is what is sent back.
        return ResponseEntity.status(HttpStatus.FOUND)
                .headers(noStore())
                .header(HttpHeaders.LOCATION, longUrl)
                .build();
    }

    /**
     * The single not-found: identical body and identical headers whether the code
     * was never issued, has expired, was deleted, was blocked or could not have
     * been a code at all. Not counted as a click on anything.
     */
    private static ResponseEntity<Object> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .headers(noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.NOT_FOUND);
    }

    private static ResponseEntity<Object> throttled(RateLimitDecision decision) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(noStore())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfter().toSeconds()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError(ErrorCode.RATE_LIMITED));
    }

    private static HttpHeaders noStore() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, NO_STORE);
        headers.set(HttpHeaders.PRAGMA, "no-cache");
        headers.set(HttpHeaders.EXPIRES, "0");
        return headers;
    }
}
