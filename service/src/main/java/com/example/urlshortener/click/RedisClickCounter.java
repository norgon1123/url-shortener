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
 */
@Component
public class RedisClickCounter implements ClickCounter {

    private static final Logger log = LoggerFactory.getLogger(RedisClickCounter.class);

    private final StringRedisTemplate redis;
    private final String pendingKey;

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> claimScript;

    public RedisClickCounter(StringRedisTemplate redis, AppProperties properties) {
        this.redis = redis;
        this.pendingKey = properties.click().keyPrefix() + "pending";
        this.claimScript = claimScript();
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
     * Takes up to {@code limit} links' deltas out of Redis. The caller now owns
     * them: they exist nowhere else until it writes them down, and it must
     * {@link #restore(UUID, long)} anything it fails to persist.
     */
    @SuppressWarnings("unchecked")
    Map<UUID, Long> claim(int limit) {
        List<String> flat;
        try {
            flat = redis.execute(claimScript, List.of(pendingKey), Integer.toString(limit));
        } catch (RuntimeException unavailable) {
            log.warn("Could not claim click deltas; retrying on the next pass: {}", unavailable.getMessage());
            return Map.of();
        }
        if (flat == null || flat.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Long> claimed = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            try {
                claimed.put(UUID.fromString(flat.get(i)), Long.parseLong(flat.get(i + 1)));
            } catch (IllegalArgumentException notOurs) {
                unreadable.add(flat.get(i));
            }
        }
        if (!unreadable.isEmpty()) {
            log.warn("Discarded {} click delta(s) whose key was not a link id", unreadable.size());
        }
        return claimed;
    }

    /** Puts a claimed delta back after a failed durable write, so the clicks are not lost. */
    void restore(UUID linkId, long delta) {
        try {
            redis.opsForHash().increment(pendingKey, linkId.toString(), delta);
        } catch (RuntimeException unavailable) {
            log.error("Lost {} click(s) on {}: the durable write failed and the delta could not be put back",
                    delta, linkId, unavailable);
        }
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> claimScript() {
        DefaultRedisScript<List> loaded = new DefaultRedisScript<>();
        loaded.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/claim_click_deltas.lua")));
        loaded.setResultType(List.class);
        return loaded;
    }
}
