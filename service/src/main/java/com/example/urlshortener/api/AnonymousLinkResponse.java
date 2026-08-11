package com.example.urlshortener.api;

import java.time.Instant;

/**
 * Body of a successful {@code POST /api/v1/public/links}.
 *
 * <p>A narrower shape than {@link LinkResponse}, and the two fields it drops are
 * the point (Q9). {@code status} and {@code clickCount} are absent because AC13
 * says nobody - signed in or not - may read an anonymous link's numbers, and a
 * body that carried them would invite a client to expect to fetch them again
 * later from an endpoint that will only ever answer 404.
 *
 * <p>What is returned is the whole of the holder's relationship with the
 * service: the code, the URL to hand out, the target it was created from, and
 * when it stops working. There is no way to read this back afterwards, so the
 * response is the only copy - which is why {@code expiresAt} is in it rather
 * than left implicit in a configured TTL.
 *
 * <p>The 201 carries no {@code Location} header. {@code /api/v1/links/{code}}
 * answers 401 without a token and 404 with one, for every caller including the
 * creator, so a {@code Location} pointing there would be a documented promise
 * that resolves for nobody.
 *
 * @param code      the short code
 * @param shortUrl  {@code app.base-url} + "/" + code, identical in form to the
 *                  authenticated create response
 * @param longUrl   the target, byte-identical to what was submitted. Host
 *                  normalisation is used for checking only and never rewrites
 *                  what is stored or served (A2).
 * @param createdAt UTC instant, ISO-8601
 * @param expiresAt UTC instant, ISO-8601: {@code createdAt} plus
 *                  {@code app.links.anonymous-ttl} (30 days by default, A9/Q3).
 *                  Never caller-supplied. After this instant the code answers
 *                  404 on the click path, exactly like an expired owned link
 *                  (AC10).
 */
public record AnonymousLinkResponse(
        String code, String shortUrl, String longUrl, Instant createdAt, Instant expiresAt) {}
