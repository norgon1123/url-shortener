package com.example.urlshortener.error;

import com.example.urlshortener.api.ApiError;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns everything the management API can refuse into the one documented body.
 *
 * <p>One handler and one {@link ErrorCode} catalogue, rather than an exception
 * class per status: the statuses and the messages then live in a single place and
 * cannot drift apart, which matters here more than usual because the branch that
 * throws and the branch that asserts on the result were written without sight of
 * each other.
 *
 * <p>Nothing here echoes request content. Messages are fixed per code, and the
 * only per-request detail that ever reaches a caller is the name of a field that
 * failed validation - never its value.
 *
 * <p>The click path does not come through here. It builds its own responses so
 * that its cache headers are identical on every one of them.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception) {
        ErrorCode code = exception.code();
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON);

        if (exception.retryAfter() != null) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfter().toSeconds()));
        }
        if (code == ErrorCode.UNAUTHORIZED) {
            // Only on unauthorized. A sign-in that answers invalid_credentials has
            // already been reached without a session, and challenging for one there
            // would say something about the account that was named.
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(new ApiError(code.wireValue(), exception.getMessage(), exception.fields()));
    }

    /** Bean validation on a request body: the field names, never the values. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return invalidRequest(fields.isEmpty() ? null : fields);
    }

    /**
     * Unparseable JSON, a body that is required and absent, or - the case this
     * project actually leans on - a property the schema does not define. Strict
     * bodies are what make a link target immutable in practice: a patch carrying
     * {@code longUrl} fails loudly here instead of being silently ignored.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.debug("Rejected an unreadable request body: {}", exception.getMostSpecificCause().getClass().getSimpleName());
        return invalidRequest(null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleBadParameterType(MethodArgumentTypeMismatchException exception) {
        return invalidRequest(Map.of(exception.getName(), "is not valid"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException exception) {
        return invalidRequest(Map.of(exception.getParameterName(), "is required"));
    }

    private static ResponseEntity<ApiError> invalidRequest(Map<String, String> fields) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError(
                        ErrorCode.INVALID_REQUEST.wireValue(),
                        ErrorCode.INVALID_REQUEST.defaultMessage(),
                        fields));
    }
}
