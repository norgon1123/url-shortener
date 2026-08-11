package com.example.urlshortener.click;

import com.example.urlshortener.config.AppProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * Click counting on Redis, drained into PostgreSQL by {@link ClickFlushJob}.
 *
 * <p>{@code HINCRBY} is atomic, so a burst of concurrent clicks on one link is
 * counted exactly rather than approximately -- the read-modify-write that a
 * per-click {@code UPDATE ... SET n = n + 1} avoids in the database is avoided
 * here too, and a counter that is right one click at a time but lossy under
 * concurrency is the defect this design exists to prevent.
 *
 * <p>All deltas live in one hash. That keeps every operation single-key, so the
 * flush stays atomic and the design survives the shared Redis turning out to be a
 * Cluster. It is also the thing to change first at real volume: one hot key is a
 * single shard's worth of write throughput, and the scale path is to shard the
 * hash by a prefix of the link id, which needs no schema change and no migration.
 *
 * <p>Nothing here throws. A click is served whether or not it can be counted --
 * that is the trade the availability criterion asks for, spelled out -- and a
 * lost increment is logged rather than silently swallowed.
 *
 * <p>Draining is a read followed by a subtraction of exactly what was written
 * durably, never a destructive claim: a claim is delivered to Redis whether or
 * not its reply arrives, so a slow server turns a timeout into clicks that
 * exist in neither store. The invariant the two halves keep between them is that
 * the durable total plus the pending delta is the number of clicks recorded, and
 * both operations preserve it whichever of them is interrupted.
 *
 * <p>That invariant is also what makes a non-exclusive read safe. Every instance
 * runs the flush schedule, so two of them can read one delta and both add it to
 * the durable column -- but each then subtracts what it wrote, the pending field
 * goes negative by the surplus, and the figure a customer is shown stays right
 * throughout. The next pass flushes that negative like any other delta and the
 * column settles back. A destructive claim bought exclusivity here at the price
 * of losing clicks outright to a timeout, which is the worse of the two and the
 * one that cannot be repaired afterwards.
 */
@Component
public class RedisClickCounter implements ClickCounter {

    private static final Logger log = LoggerFactory.getLogger(RedisClickCounter.class);

    private final StringRedisTemplate redis;
    private final String pendingKey;

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> readScript;

    public RedisClickCounter(StringRedisTemplate redis, AppProperties properties) {
        this.redis = redis;
        this.pendingKey = properties.click().keyPrefix() + "pending";
        this.readScript = readScript();
    }

    @Override
    public void record(UUID linkId) {
        try {
            redis.opsForHash().increment(pendingKey, linkId.toString(), 1L);
        } catch (RuntimeException unavailable) {
            // Synchronous on purpose. Handing this to an executor would add a queue
            // that drops clicks under load, which trades a bounded outage for an
            // unbounded inaccuracy; the command timeout is what bounds the cost.
            log.warn("Click on {} not counted; the counting tier is unavailable: {}",
                    linkId, unavailable.getMessage());
        }
    }

    @Override
    public long pendingDelta(UUID linkId) {
        try {
            Object value = redis.opsForHash().get(pendingKey, linkId.toString());
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (RuntimeException unavailable) {
            log.debug("Pending delta for {} unavailable: {}", linkId, unavailable.getMessage());
            return 0L;
        }
    }

    /**
     * Reads up to {@code limit} links' un-flushed deltas, leaving them in place.
     *
     * <p>The caller writes them durably and then {@link #settle(UUID, long)}s
     * exactly what it wrote. Anything it does not settle -- because the write
     * failed, because this call timed out, because the process died -- is simply
     * read again on the next pass, so no interruption can lose a click.
     */
    @SuppressWarnings("unchecked")
    Map<UUID, Long> readPending(int limit) {
        List<String> flat;
        try {
            flat = redis.execute(readScript, List.of(pendingKey), Integer.toString(limit));
        } catch (RuntimeException unavailable) {
            log.warn("Could not read click deltas; retrying on the next pass: {}", unavailable.getMessage());
            return Map.of();
        }
        if (flat == null || flat.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Long> pending = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            try {
                pending.put(UUID.fromString(flat.get(i)), Long.parseLong(flat.get(i + 1)));
            } catch (IllegalArgumentException notOurs) {
                unreadable.add(flat.get(i));
            }
        }
        if (!unreadable.isEmpty()) {
            log.warn("Ignored {} click delta(s) whose key was not a link id", unreadable.size());
        }
        return pending;
    }

    /**
     * Subtracts a delta that is now in the durable total, so it is not written
     * twice. Clicks that arrived since the read keep their place in the hash --
     * this decrements by the amount written rather than deleting the field.
     */
    void settle(UUID linkId, long delta) {
        try {
            redis.opsForHash().increment(pendingKey, linkId.toString(), -delta);
        } catch (RuntimeException unavailable) {
            // The one direction this design can still get wrong, and the smaller
            // of the two: the durable write has committed and the delta has not
            // been taken back, so the next pass adds these clicks a second time.
            // Loud, because it is the only way an overcount reaches a customer.
            log.error("Wrote {} click(s) for {} durably but could not settle the pending delta; "
                    + "they will be counted again on the next pass", delta, linkId, unavailable);
        }
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> readScript() {
        DefaultRedisScript<List> loaded = new DefaultRedisScript<>();
        loaded.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/read_click_deltas.lua")));
        loaded.setResultType(List.class);
        return loaded;
    }
}
