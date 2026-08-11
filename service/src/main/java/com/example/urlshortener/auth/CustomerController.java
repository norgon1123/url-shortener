package com.example.urlshortener.auth;

import com.example.urlshortener.api.SignUpRequest;
import com.example.urlshortener.api.SignUpResponse;
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
 * Self-service sign-up.
 *
 * <p>201 rather than 200, because an addressable account is created - contrast
 * {@link SignInController}, which is 200 precisely because nothing is. No
 * {@code Location} header: there is no {@code GET /api/v1/customers/{id}} in this
 * build, so the header would name a path that answers 401 without a token and 405
 * with one, for every caller including the account just created. The id is in the
 * body, which is what a client actually needs. No session token either: signing in
 * is the next request, against the endpoint that already knows how to issue one.
 *
 * <p>Throttled per client address before any hashing or storage happens. This is
 * an unauthenticated endpoint that does ~25 ms of memory-hard work and then writes
 * to PostgreSQL, and the bucket is also the only thing bounding the account
 * enumeration that a visible duplicate refusal necessarily permits, so it is
 * load-bearing rather than defensive tidiness.
 */
@RestController
public class CustomerController {

    private final SignUpService signUpService;
    private final RateLimitGuard rateLimitGuard;

    public CustomerController(SignUpService signUpService, RateLimitGuard rateLimitGuard) {
        this.signUpService = signUpService;
        this.rateLimitGuard = rateLimitGuard;
    }

    @PostMapping("/api/v1/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public SignUpResponse signUp(@Valid @RequestBody SignUpRequest request, HttpServletRequest httpRequest) {
        rateLimitGuard.requireByAddress(RateLimitBucket.SIGN_UP, httpRequest.getRemoteAddr());
        return signUpService.signUp(request);
    }
}
