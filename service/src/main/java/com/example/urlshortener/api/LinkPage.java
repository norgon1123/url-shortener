package com.example.urlshortener.api;

import java.util.List;

/**
 * Body of {@code GET /api/v1/links}: one page of the caller's own links.
 *
 * <p>A hand-rolled page rather than Spring Data's {@code Page}, whose JSON
 * shape is version-dependent and carries fields ({@code pageable},
 * {@code sort}, {@code numberOfElements}) that would become part of a frozen
 * public contract by accident.
 *
 * <p>Ordering is fixed: {@code createdAt} descending, then {@code code}
 * ascending as a tiebreak. Blind test authoring needs a total order - two links
 * created in the same millisecond must not be able to swap places between runs.
 *
 * @param items         links owned by the caller, including expired, deleted and
 *                      blocked ones. Never another customer's (AC13).
 * @param page          zero-based index of this page
 * @param size          maximum items per page
 * @param totalElements total links owned by the caller
 * @param totalPages    total pages at this size; 0 when the caller has no links
 */
public record LinkPage(
        List<LinkResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {}
