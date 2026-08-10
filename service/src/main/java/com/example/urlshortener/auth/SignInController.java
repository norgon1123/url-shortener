package com.example.urlshortener.auth;

import com.example.urlshortener.api.SignInRequest;
import com.example.urlshortener.api.SignInResponse;
import com.example.urlshortener.ratelimit.RateLimitBucket;
import com.example.urlshortener.ratelimit.RateLimitGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in.
 *
 * <p>200 rather than 201: no addressable resource is created, and the token is
 * the whole of the response.
 *
 * <p>Throttled per client address before the password is checked. An unthrottled
 * sign-in is an open credential-stuffing target, and the promise that a stolen
 * database yields no passwords is worth very little if they can be guessed at the
 * front door instead.
 */
@RestController
public class SignInController {

    private final SignInService signInService;
    private final RateLimitGuard rateLimitGuard;

    public SignInController(SignInService signInService, RateLimitGuard rateLimitGuard) {
        this.signInService = signInService;
        this.rateLimitGuard = rateLimitGuard;
    }

    @PostMapping("/api/v1/sessions")
    public ResponseEntity<SignInResponse> signIn(
            @Valid @RequestBody SignInRequest request, HttpServletRequest httpRequest) {

        rateLimitGuard.requireByAddress(RateLimitBucket.SIGN_IN, httpRequest.getRemoteAddr());
        return ResponseEntity.ok(signInService.signIn(request));
    }
}
