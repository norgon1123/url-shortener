package com.example.urlshortener.click;

import com.example.urlshortener.config.AppProperties;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains click deltas from Redis into the durable total.
 *
 * <p>The flush interval does not affect the figure a customer is shown: a read
 * returns the durable total plus whatever is still pending, so a click made a
 * second ago is already in the number. What the interval controls is how much is
 * at risk if the counting tier is lost, and how much write traffic reaches
 * PostgreSQL.
 *
 * <p>The claim is atomic and destructive, so between claiming a delta and
 * committing it this process is the only place those clicks exist. A failed write
 * therefore has to put them back, and does.
 */
@Component
public class ClickFlushJob {

    private static final Logger log = LoggerFactory.getLogger(ClickFlushJob.class);

    private final RedisClickCounter counter;
    private final ClickFlushWriter writer;
    private final int batchSize;

    public ClickFlushJob(RedisClickCounter counter, ClickFlushWriter writer, AppProperties properties) {
        this.counter = counter;
        this.writer = writer;
        this.batchSize = properties.click().flushBatchSize();
    }

    @Scheduled(fixedDelayString = "${app.click.flush-interval:PT5S}")
    public void flush() {
        Map<UUID, Long> claimed = counter.claim(batchSize);
        if (claimed.isEmpty()) {
            return;
        }
        int written = 0;
        for (Map.Entry<UUID, Long> delta : claimed.entrySet()) {
            try {
                writer.write(delta.getKey(), delta.getValue());
                written++;
            } catch (RuntimeException failed) {
                log.warn("Could not write {} click(s) for {}; returning them to the pending tier: {}",
                        delta.getValue(), delta.getKey(), failed.getMessage());
                counter.restore(delta.getKey(), delta.getValue());
            }
        }
        log.debug("Flushed clicks for {} of {} link(s)", written, claimed.size());
    }

}
