package com.example.urlshortener.auth;

import java.util.UUID;

/**
 * The authenticated caller, as recovered from a verified session token.
 *
 * <p>Ownership is on this id and nothing else. Every owner-scoped query filters
 * by it in the query itself rather than fetching and then comparing: a check
 * after the fetch is one forgotten branch away from a cross-tenant read, and
 * AC13 is not a check, it is an invariant.
 *
 * @param id    customer id, the owner column on every link
 * @param email login identity, carried for logging and for the sign-in response
 */
public record CurrentCustomer(UUID id, String email) {}
