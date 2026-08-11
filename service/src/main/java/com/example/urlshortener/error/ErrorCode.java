package com.example.urlshortener.error;

import org.springframework.http.HttpStatus;

/**
 * The complete error catalogue of the HTTP API.
 *
 * <p>Every non-2xx response this service produces carries one of these codes in
 * the {@code error} field of {@link com.example.urlshortener.api.ApiError}, with
 * the status and the default message fixed here. Freezing the catalogue as an
 * enum is what lets a test author, who never sees the implementation, assert on
 * an exact body.
 *
 * <p>Two absences are deliberate and are the security-relevant half of this
 * type:
 *
 * <ul>
 *   <li>There is no {@code forbidden}/403. A link that exists but belongs to
 *       another customer answers {@link #NOT_FOUND}, identically to a code that
 *       was never issued. A 403/404 split is an existence oracle, which is
 *       precisely what AC13, AC15 and AC16 are trying to close.</li>
 *   <li>There is no {@code gone}/410 for an expired or deleted link. 410 tells a
 *       caller "this existed", which distinguishes an expired link from an
 *       unissued code and hands an enumerator a free signal. Everything
 *       unusable - unknown, expired, deleted, blocked, someone else's - is
 *       {@link #NOT_FOUND} with a byte-identical body.</li>
 * </ul>
 *
 * <p>{@link #ACCOUNT_UNAVAILABLE} is the one entry that cuts against that
 * grain, and it was added knowingly rather than by drift. AC6 requires a second
 * sign-up for an existing address to be refused and visibly so; any visible
 * refusal tells the caller that address has an account. There is no wording of
 * that refusal which is not an oracle, so the choice was which status carries
 * it, not whether to disclose. It is reachable only from
 * {@code POST /api/v1/customers}, it is throttled by an IP-keyed bucket that is
 * load-bearing rather than decorative, and it says nothing beyond
 * "unavailable" - no owner, no creation date, no distinction between taken and
 * reserved. See the design rationale, and Q2 in the clarification: this one
 * belongs to whoever approves the contract.
 */
public enum ErrorCode {

    /** Malformed or semantically invalid request body, path or query. */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid_request", "The request is not valid."),

    /**
     * Sign-in failed. One code for "no such account" and "wrong password": a
     * split would enumerate customer accounts.
     */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid email or password."),

    /** Missing, malformed, expired or unverifiable session token. */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication required."),

    /** The single not-found answer. See the class note. */
    NOT_FOUND(HttpStatus.NOT_FOUND, "not_found", "Not found"),

    /**
     * A requested alias is already in use (AC6). Deliberately says nothing about
     * who holds it or where it points.
     */
    ALIAS_UNAVAILABLE(HttpStatus.CONFLICT, "alias_unavailable", "That short code is not available."),

    /** The caller's own link is deleted or blocked and can no longer be edited. */
    LINK_NOT_MODIFIABLE(HttpStatus.CONFLICT, "link_not_modifiable", "This link can no longer be modified."),

    /**
     * Sign-up named an account name that already exists (AC6).
     *
     * <p>409 rather than 400, and worded to match {@link #ALIAS_UNAVAILABLE}
     * deliberately: both are "you asked to create something under a name that
     * is taken", which is what 409 Conflict means, and both refuse without
     * saying who holds the name or when they took it. 400 would carry the same
     * disclosure under a status that says the request was malformed, which it
     * was not - it was well formed and lost a race, possibly by two years.
     *
     * <p>Only ever produced by {@code POST /api/v1/customers}. It is decided by
     * the unique constraint over the lower-cased email, not by a preceding
     * lookup, so the concurrent case AC6 names resolves the same way as the
     * sequential one.
     */
    ACCOUNT_UNAVAILABLE(HttpStatus.CONFLICT, "account_unavailable", "That account name is not available."),

    /**
     * The URL parses but may not be shortened: a denylisted target (AC21), an
     * internal/loopback/private host, or this service's own domain (A14).
     *
     * <p>One code covers all three so that the response is not a probe of the
     * denylist's contents.
     */
    URL_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "url_rejected", "The submitted URL cannot be shortened."),

    /** A token bucket is empty (AC19). Always accompanied by {@code Retry-After}. */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests."),

    /**
     * A dependency the write path needs is unavailable. Never returned by the
     * click path: AC20 says a click is served in preference to accepting a new
     * link, so degradation is spent on creation, not on redirection.
     */
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable",
            "Temporarily unable to accept new links.");

    private final HttpStatus status;
    private final String wireValue;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String wireValue, String defaultMessage) {
        this.status = status;
        this.wireValue = wireValue;
        this.defaultMessage = defaultMessage;
    }

    /** HTTP status this code is always returned with. */
    public HttpStatus status() {
        return status;
    }

    /** Value of the {@code error} field on the wire. */
    public String wireValue() {
        return wireValue;
    }

    /** Value of the {@code message} field unless an operation documents another. */
    public String defaultMessage() {
        return defaultMessage;
    }
}
