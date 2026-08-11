package com.example.urlshortener.abuse;

import com.example.urlshortener.api.AbuseReportRequest;
import com.example.urlshortener.auth.CurrentCustomer;
import com.example.urlshortener.ratelimit.RateLimitBucket;
import com.example.urlshortener.ratelimit.RateLimitGuard;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reporting a link as abusive.
 *
 * <p>202 with no body for any syntactically acceptable code, whether or not it
 * resolves and whoever owns it. The response says only that the report was
 * accepted, which is the point: anything that varied with whether the code existed
 * would be an existence oracle handed to an untrusted caller.
 *
 * <p>The body is optional. What is recorded is the reporter and the time; a reason
 * is useful to whoever reviews the report later and is not needed to accept it.
 *
 * <p>Accepted is not the same as acted on: {@link AbuseReportService} decides
 * whether the report also takes the link down, and the response is the same either
 * way for the reason above.
 */
@RestController
public class AbuseReportController {

    private final AbuseReportService abuseReportService;
    private final RateLimitGuard rateLimitGuard;

    public AbuseReportController(AbuseReportService abuseReportService, RateLimitGuard rateLimitGuard) {
        this.abuseReportService = abuseReportService;
        this.rateLimitGuard = rateLimitGuard;
    }

    @PostMapping("/api/v1/links/{code}/abuse-reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(
            CurrentCustomer reporter,
            @PathVariable String code,
            @Valid @RequestBody(required = false) AbuseReportRequest request) {

        rateLimitGuard.requireByCustomer(RateLimitBucket.ABUSE_REPORT, reporter.id());
        abuseReportService.report(reporter, code, request == null ? null : request.reason());
    }
}
