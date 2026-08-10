package com.example.urlshortener.link;

import java.util.Set;

/**
 * What a customer-chosen alias may be (AC5/AC6/A3).
 *
 * <p>Aliases share one namespace with generated codes. They have to: if they
 * lived apart, "already taken" would depend on which namespace the caller
 * happened to land in, and AC6's rejection would be meaningless. The consequence
 * is stated rather than hidden - an alias is chosen to be memorable and is
 * therefore guessable, so AC16's unguessability guarantee covers generated codes
 * only.
 *
 * <p>Matching is case-sensitive, like the generated codes it shares a namespace
 * with; folding case would throw away entropy from every generated code to make
 * aliases tidier. Reserved words, by contrast, are matched case-insensitively:
 * the point of reserving {@code api} is that {@code API} must not shadow a route
 * either.
 */
public class AliasPolicy {

    public static final int MIN_LENGTH = 3;

    public static final int MAX_LENGTH = 64;

    /** The full charset rule, identical to the one on the request DTO. */
    public static final String PATTERN = "^[A-Za-z0-9_-]{3,64}$";

    /**
     * Codes no customer may take, because the redirect is mapped at the root and
     * an alias here would shadow a real route or a well-known path.
     *
     * <p>{@code actuator} and the paths under it matter most: the health and
     * metrics endpoints must stay reachable once a catch-all {@code /{code}} is
     * mapped, and an alias that shadowed them would take out monitoring rather
     * than return a redirect.
     */
    public static final Set<String> RESERVED_CODES = Set.of(
            "api",
            "actuator",
            "health",
            "info",
            "metrics",
            "prometheus",
            "admin",
            "login",
            "logout",
            "signin",
            "sessions",
            "links",
            "static",
            "assets",
            "docs",
            "status",
            "abuse",
            "support",
            "help",
            "terms",
            "privacy",
            "www",
            "robots.txt",
            "favicon.ico",
            "sitemap.xml",
            "index.html",
            "security.txt",
            ".well-known");

    /** True if the alias matches {@link #PATTERN}. */
    public boolean isWellFormed(String alias) {
        throw new UnsupportedOperationException("Frozen contract skeleton; implemented by the implement node.");
    }

    /** True if the alias is reserved, compared case-insensitively. */
    public boolean isReserved(String alias) {
        throw new UnsupportedOperationException("Frozen contract skeleton; implemented by the implement node.");
    }

    /**
     * Validates a requested alias before any database work.
     *
     * @throws com.example.urlshortener.error.ApiException {@code invalid_request}
     *         (400) when the alias is malformed or reserved. Note the status: a
     *         reserved word is not "taken by someone", so it is not a 409, and
     *         answering 409 would tell a caller that a reserved word exists as
     *         somebody's link.
     */
    public void requireAcceptable(String alias) {
        throw new UnsupportedOperationException("Frozen contract skeleton; implemented by the implement node.");
    }
}
