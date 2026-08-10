package com.example.urlshortener.auth;

import com.example.urlshortener.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a verified session on the management API.
 *
 * <p>A filter this codebase owns rather than Spring Security's chain. The
 * auto-configured chain secures every mapping in the application, and the
 * root-level short-code redirect must work with no credentials at all -- getting
 * that wrong is one missing matcher away, and the failure mode is a green build
 * with a product that 401s every click. Here the surface is explicit: this filter
 * is registered for {@code /api/v1/*} and nothing else, so the click path and the
 * actuator endpoints are outside it by construction rather than by exception.
 *
 * <p>Sign-in is the one unauthenticated route under that prefix, for the obvious
 * reason.
 *
 * <p>Every failure -- absent, malformed, expired, forged, signed by another key --
 * produces the same 401 with the same body. A caller must not be able to learn
 * which of those it was.
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    /** Where the verified caller is left for {@link CurrentCustomerArgumentResolver}. */
    public static final String CURRENT_CUSTOMER_ATTRIBUTE = "urlshortener.currentCustomer";

    private static final String SESSIONS_PATH = "/api/v1/sessions";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtVerifier verifier;
    private final ObjectMapper objectMapper;

    public SessionAuthenticationFilter(JwtVerifier verifier, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SESSIONS_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Optional<CurrentCustomer> caller = bearerToken(request).flatMap(verifier::verify);
        if (caller.isEmpty()) {
            writeUnauthorized(response);
            return;
        }
        request.setAttribute(CURRENT_CUSTOMER_ATTRIBUTE, caller.get());
        chain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(header.substring(BEARER_PREFIX.length()).trim());
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiError.UNAUTHORIZED));
    }
}
