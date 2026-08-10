package com.example.urlshortener.click;

import com.example.urlshortener.repository.LinkRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one link's drained clicks to the durable total.
 *
 * <p>A separate bean from {@link ClickFlushJob} because the transaction boundary
 * is the point: a self-invoked {@code @Transactional} method does not go through
 * the proxy and would run with no transaction at all, which is a bug that costs
 * nothing at compile time and shows up as a modifying query refusing to execute.
 *
 * <p>One transaction per link, so a single row that will not update does not
 * strand every other link's clicks behind it.
 */
@Service
public class ClickFlushWriter {

    private static final Logger log = LoggerFactory.getLogger(ClickFlushWriter.class);

    private final LinkRepository links;

    public ClickFlushWriter(LinkRepository links) {
        this.links = links;
    }

    @Transactional
    public void write(UUID linkId, long delta) {
        if (links.addClicks(linkId, delta) == 0) {
            // Links are soft-deleted and never removed, so this cannot happen in
            // this schema. If it ever does, the clicks are gone and that is worth
            // a loud line rather than a silent zero.
            log.error("Discarded {} click(s) for unknown link {}", delta, linkId);
        }
    }
}
