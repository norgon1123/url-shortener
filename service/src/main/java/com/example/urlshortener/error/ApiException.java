package com.example.urlshortener.error;

import java.time.Duration;
import java.util.Map;

/**
 * The one exception type the HTTP layer translates.
 *
 * <p>A single type with an {@link ErrorCode} rather than a class per status: the
 * error catalogue is then in one place and cannot drift between the branch that
 * throws and the branch that asserts, and one {@code @ExceptionHandler} maps
 * every case.
 *
 * <p>Bodies below are constructor calls, not logic. {@code implement} adds the
 * handler that turns one of these into {@link com.example.urlshortener.api.ApiError}
 * plus the documented status and headers.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient Map<String, String> fields;
    private final transient Duration retryAfter;

    public ApiException(ErrorCode code, String message, Map<String, String> fields, Duration retryAfter) {
        super(message);
        this.code = code;
        this.fields = fields;
        this.retryAfter = retryAfter;
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    /** Which catalogue entry this is; carries the status. */
    public ErrorCode code() {
        return code;
    }

    /** Per-field detail for {@link ErrorCode#INVALID_REQUEST}; null otherwise. */
    public Map<String, String> fields() {
        return fields;
    }

    /** Seconds to advertise in {@code Retry-After}; null unless rate limited. */
    public Duration retryAfter() {
        return retryAfter;
    }

    public static ApiException invalidRequest(String message, Map<String, String> fields) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message, fields, null);
    }

    public static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, ErrorCode.INVALID_CREDENTIALS.defaultMessage());
    }

    public static ApiException unauthorized() {
        return new ApiException(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());
    }

    /**
     * The only not-found there is. Callers must not add a reason: the message is
     * fixed so that unknown, expired, deleted, blocked and other-customer codes
     * are indistinguishable (A4).
     */
    public static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage());
    }

    public static ApiException aliasUnavailable() {
        return new ApiException(ErrorCode.ALIAS_UNAVAILABLE, ErrorCode.ALIAS_UNAVAILABLE.defaultMessage());
    }

    public static ApiException linkNotModifiable() {
        return new ApiException(ErrorCode.LINK_NOT_MODIFIABLE, ErrorCode.LINK_NOT_MODIFIABLE.defaultMessage());
    }

    /**
     * The sign-up refusal. Like {@link #aliasUnavailable()}, callers must not
     * add detail: the message is fixed so that "taken years ago" and "taken a
     * millisecond ago by the request racing yours" are one answer, and so that
     * the body carries nothing about the account beyond the fact that the name
     * is not available.
     */
    public static ApiException accountUnavailable() {
        return new ApiException(ErrorCode.ACCOUNT_UNAVAILABLE, ErrorCode.ACCOUNT_UNAVAILABLE.defaultMessage());
    }

    public static ApiException urlRejected() {
        return new ApiException(ErrorCode.URL_REJECTED, ErrorCode.URL_REJECTED.defaultMessage());
    }

    public static ApiException rateLimited(Duration retryAfter) {
        return new ApiException(ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.defaultMessage(), null, retryAfter);
    }

    public static ApiException dependencyUnavailable(String message) {
        return new ApiException(ErrorCode.SERVICE_UNAVAILABLE, message);
    }
}
