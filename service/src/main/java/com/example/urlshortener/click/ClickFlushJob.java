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
 * <p>A pass reads, writes, then subtracts what it wrote. Reading does not remove
 * anything, so at no point are a link's clicks only in this process: a write that
 * fails, a pass that is interrupted or a Redis call whose reply never arrives all
 * leave the delta where it was and cost nothing but a repeated read next time.
 * The order is what keeps the total honest -- the durable write commits before
 * the delta is taken back, so the pair is briefly counted twice rather than
 * briefly not at all, which is the direction a customer can be shown.
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
        Map<UUID, Long> pending = counter.readPending(batchSize);
        if (pending.isEmpty()) {
            return;
        }
        int written = 0;
        for (Map.Entry<UUID, Long> delta : pending.entrySet()) {
            try {
                writer.write(delta.getKey(), delta.getValue());
            } catch (RuntimeException failed) {
                log.warn("Could not write {} click(s) for {}; they stay pending and are retried next pass: {}",
                        delta.getValue(), delta.getKey(), failed.getMessage());
                continue;
            }
            counter.settle(delta.getKey(), delta.getValue());
            written++;
        }
        log.debug("Flushed clicks for {} of {} link(s)", written, pending.size());
    }

}
