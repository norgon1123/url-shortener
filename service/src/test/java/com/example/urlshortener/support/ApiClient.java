package com.example.urlshortener.support;

import com.example.urlshortener.api.ApiError;
import com.example.urlshortener.api.LinkPage;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.api.SignInResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The one way a test reaches the service.
 *
 * <p>Two decisions in here are the whole reason this class exists rather than
 * each test using whatever client it fancied.
 *
 * <p><strong>Redirects are never followed.</strong> The click path answers 302
 * and the response <em>is</em> the thing under test: its status, its
 * {@code Location}, and its cache headers. A client that quietly followed the
 * redirect would report the status of {@code example.com}, would need the
 * internet to do it, and would make AC2 and AC4 untestable while looking like
 * they passed. {@link HttpClient.Redirect#NEVER} is set once, here.
 *
 * <p><strong>Nothing throws on a non-2xx.</strong> Most of what this contract
 * fixes is error behaviour - 400, 401, 404, 409, 422, 429, 503 - so every call
 * returns the raw {@link HttpResponse} with the body as a {@code String}. The
 * body is left unparsed so a test can compare it byte for byte: the single 404
 * body is required to be identical across five different causes on two
 * surfaces, and a parsed object cannot show that.
 *
 * <p>The paths are the frozen ones. A test never spells a URL itself, so a route
 * that moves moves in one file.
 */
public final class ApiClient {

    /** {@code POST} here to sign in. */
    public static final String SESSIONS_PATH = "/api/v1/sessions";

    /** Collection resource for links. */
    public static final String LINKS_PATH = "/api/v1/links";

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final HttpClient client;
    private final String baseUrl;

    /**
     * @param baseUrl origin of the running service, e.g. {@code http://localhost:12345}
     */
    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** The origin this client talks to; the same value the service uses to build {@code shortUrl}. */
    public String baseUrl() {
        return baseUrl;
    }

    // ---- transport --------------------------------------------------------

    /**
     * Sends any method to any path, with or without a body and a bearer credential.
     *
     * @param method   HTTP method, uppercase
     * @param path     absolute path beginning with {@code /}, already encoded
     * @param jsonBody request body, or null for no body
     * @param bearer   session credential, or null to send none
     */
    public HttpResponse<String> send(String method, String path, String jsonBody, String bearer) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30));
        if (jsonBody == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json");
        }
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("request to " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling " + path, e);
        }
    }

    // ---- sessions ---------------------------------------------------------

    /** Raw sign-in, for the cases where the response itself is under test. */
    public HttpResponse<String> signIn(String email, String plaintext) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", plaintext);
        return send("POST", SESSIONS_PATH, toJson(body), null);
    }

    /** Sign-in with an arbitrary body, for malformed and unknown-property cases. */
    public HttpResponse<String> signInRaw(String jsonBody) {
        return send("POST", SESSIONS_PATH, jsonBody, null);
    }

    /**
     * Signs in and hands back the bearer credential, for the great majority of
     * tests where getting a session is a precondition rather than the subject.
     */
    public String signInFor(Fixtures.SeededCustomer customer) {
        HttpResponse<String> response = signIn(customer.email(), customer.plaintext());
        if (response.statusCode() != 200) {
            // A precondition that failed, said out loud. Parsing a 429 or a 401
            // body into a SignInResponse yields a null accessToken, every later
            // call in the test goes out unauthenticated, and the test fails much
            // further downstream with a NullPointerException on a code that was
            // never issued - pointing at everything except the sign-in.
            throw new IllegalStateException(
                    "could not sign in as " + customer.email() + ": HTTP " + response.statusCode()
                            + " " + response.body());
        }
        return asSession(response).accessToken();
    }

    // ---- links ------------------------------------------------------------

    /** Create with the default expiry and a generated code. */
    public HttpResponse<String> createLink(String bearer, String longUrl) {
        return createLink(bearer, longUrl, null, null);
    }

    /**
     * Create, with any combination of the optional fields. Null fields are
     * omitted from the body entirely rather than sent as JSON null, because
     * "absent" and "null" are different requests against a strict body.
     */
    public HttpResponse<String> createLink(String bearer, String longUrl, String alias, Instant expiresAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (longUrl != null) {
            body.put("longUrl", longUrl);
        }
        if (alias != null) {
            body.put("alias", alias);
        }
        if (expiresAt != null) {
            body.put("expiresAt", expiresAt.toString());
        }
        return send("POST", LINKS_PATH, toJson(body), bearer);
    }

    /** Create with a body this client would not otherwise produce (unknown properties, junk). */
    public HttpResponse<String> createLinkRaw(String bearer, String jsonBody) {
        return send("POST", LINKS_PATH, jsonBody, bearer);
    }

    /** Fetch one link and its click count. */
    public HttpResponse<String> getLink(String bearer, String code) {
        return send("GET", LINKS_PATH + "/" + encode(code), null, bearer);
    }

    /** List the caller's links; null page or size leaves the parameter off. */
    public HttpResponse<String> listLinks(String bearer, Integer page, Integer size) {
        StringBuilder query = new StringBuilder();
        if (page != null) {
            query.append(query.isEmpty() ? "?" : "&").append("page=").append(page);
        }
        if (size != null) {
            query.append(query.isEmpty() ? "?" : "&").append("size=").append(size);
        }
        return send("GET", LINKS_PATH + query, null, bearer);
    }

    /** Change a link's expiry. */
    public HttpResponse<String> updateExpiry(String bearer, String code, Instant expiresAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (expiresAt != null) {
            body.put("expiresAt", expiresAt.toString());
        }
        return send("PATCH", LINKS_PATH + "/" + encode(code), toJson(body), bearer);
    }

    /**
     * Patch with an arbitrary body. This is how immutability of the target URL is
     * shown: {@code {"longUrl": "..."}} must be refused, not silently ignored.
     */
    public HttpResponse<String> updateLinkRaw(String bearer, String code, String jsonBody) {
        return send("PATCH", LINKS_PATH + "/" + encode(code), jsonBody, bearer);
    }

    /** Take the caller's link down. */
    public HttpResponse<String> deleteLink(String bearer, String code) {
        return send("DELETE", LINKS_PATH + "/" + encode(code), null, bearer);
    }

    /** Report a link as abusive; a null reason sends no body at all. */
    public HttpResponse<String> reportAbuse(String bearer, String code, String reason) {
        String path = LINKS_PATH + "/" + encode(code) + "/abuse-reports";
        if (reason == null) {
            return send("POST", path, null, bearer);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        return send("POST", path, toJson(body), bearer);
    }

    // ---- the click path ---------------------------------------------------

    /**
     * Follows a short link the way a browser would, except that the redirect is
     * not followed and no credential is sent - the click path takes none, ever.
     *
     * <p>The route lives at the root of the namespace, so the request goes to
     * {@code /{code}} and not under any prefix. That is the one route whose shape
     * a blind test author is most likely to get wrong, which is why no test
     * builds this URL itself.
     */
    public HttpResponse<String> click(String code) {
        return send("GET", "/" + encode(code), null, null);
    }

    /**
     * The same route with {@code HEAD}. Spring dispatches HEAD to the GET
     * mapping, so this must answer with the same status and headers and no body;
     * there is no separate operation and no second annotation.
     */
    public HttpResponse<String> clickHead(String code) {
        return send("HEAD", "/" + encode(code), null, null);
    }

    /**
     * A click carrying a credential, to show that presenting one changes nothing:
     * the redirect is public and is not authenticated either way.
     */
    public HttpResponse<String> clickWithBearer(String code, String bearer) {
        return send("GET", "/" + encode(code), null, bearer);
    }

    /**
     * A raw request to the root namespace, path passed through unencoded, for
     * codes that could not be produced by {@link #encode(String)} and for methods
     * the click path does not offer.
     */
    public HttpResponse<String> rootRequest(String method, String rawPath, String jsonBody) {
        return send(method, rawPath, jsonBody, null);
    }

    // ---- parsing ----------------------------------------------------------

    /** The response body as a {@link LinkResponse}. */
    public static LinkResponse asLink(HttpResponse<String> response) {
        return parse(response.body(), LinkResponse.class);
    }

    /** The response body as a page of links. */
    public static LinkPage asPage(HttpResponse<String> response) {
        return parse(response.body(), LinkPage.class);
    }

    /** The response body as an error. */
    public static ApiError asError(HttpResponse<String> response) {
        return parse(response.body(), ApiError.class);
    }

    /** The response body as a sign-in result. */
    public static SignInResponse asSession(HttpResponse<String> response) {
        return parse(response.body(), SignInResponse.class);
    }

    /** The response body as a JSON tree, for shapes with no frozen type. */
    public static JsonNode asTree(HttpResponse<String> response) {
        return parse(response.body(), JsonNode.class);
    }

    /**
     * A response header, or empty when it is absent - never an exception. The
     * absence of {@code Retry-After} or {@code Cache-Control} is itself something
     * a test needs to be able to talk about.
     */
    public static Optional<String> header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name);
    }

    /** Serialises a map to JSON, for a test that wants to hand-build a body. */
    public static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("could not serialise a request body", e);
        }
    }

    private static <T> T parse(String body, Class<T> type) {
        try {
            return JSON.readValue(body, type);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "response body was not a " + type.getSimpleName() + ": " + body, e);
        }
    }

    private static String encode(String pathSegment) {
        return URLEncoder.encode(pathSegment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
