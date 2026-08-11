package com.example.urlshortener.api;

import com.example.urlshortener.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * The error body. Every non-2xx response in this API has this shape and no
 * other.
 *
 * <p>{@code fields} is omitted from the JSON when null ({@code NON_NULL}), which
 * is not cosmetic: it is what makes the not-found body exactly
 * <pre>{"error":"not_found","message":"Not found"}</pre>
 * on the click path and on every owner-scoped endpoint alike. A response that
 * differed by so much as a null field between "never issued" and "not yours"
 * would be the existence oracle A4 exists to prevent.
 *
 * @param error   machine-readable code; one of {@link ErrorCode#wireValue()}
 * @param message human-readable, fixed per code, never echoes request content
 * @param fields  per-field messages for {@code invalid_request} only
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, Map<String, String> fields) {

    /** The single not-found body, byte-for-byte. */
    public static final ApiError NOT_FOUND =
            new ApiError(ErrorCode.NOT_FOUND.wireValue(), ErrorCode.NOT_FOUND.defaultMessage(), null);

    /** The body returned when no valid session accompanies an /api/v1 request. */
    public static final ApiError UNAUTHORIZED =
            new ApiError(ErrorCode.UNAUTHORIZED.wireValue(), ErrorCode.UNAUTHORIZED.defaultMessage(), null);

    public ApiError(ErrorCode code) {
        this(code.wireValue(), code.defaultMessage(), null);
    }
}
