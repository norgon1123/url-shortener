package com.example.urlshortener.ratelimit;

import com.example.urlshortener.config.AppProperties;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * Token buckets in Redis, so a limit is a limit across every instance rather than
 * per instance times the replica count.
 *
 * <p>The whole decision is one round trip to a Lua script, which is what makes it
 * atomic: read-modify-write from Java would let two concurrent requests both see
 * the last token.
 *
 * <p><strong>It fails open.</strong> Rate limiting is a defence against abuse,
 * not a correctness control. A Redis outage that turned every click into a 429
 * would be a self-inflicted outage of exactly the path that is supposed to stay
 * up when everything else is unhappy, so an unreachable limiter allows the
 * request and says so in the log.
 */
@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redis;
    private final AppProperties.RateLimit settings;

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis, AppProperties properties) {
        this.redis = redis;
        this.settings = properties.rateLimit();
        this.script = tokenBucketScript();
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitDecision consume(RateLimitBucket bucket, String key) {
        if (!settings.enabled()) {
            return RateLimitDecision.ALLOWED;
        }
        try {
            List<Long> result = redis.execute(
                    script,
                    List.of(keyFor(bucket, key)),
                    Integer.toString(capacityOf(bucket)),
                    Long.toString(settings.window().toMillis()));

            if (result == null || result.size() < 3) {
                return RateLimitDecision.ALLOWED;
            }
            boolean allowed = result.get(0) == 1L;
            return new RateLimitDecision(allowed, result.get(1), retryAfter(result.get(2)));
        } catch (RuntimeException unavailable) {
            log.warn("Rate limiter unavailable for bucket {}; allowing the request: {}",
                    bucket, unavailable.getMessage());
            return RateLimitDecision.ALLOWED;
        }
    }

    private static String keyFor(RateLimitBucket bucket, String key) {
        return KEY_PREFIX + bucket.name().toLowerCase(Locale.ROOT) + ":" + key;
    }

    private int capacityOf(RateLimitBucket bucket) {
        return switch (bucket) {
            case CLICK -> settings.clickPerMinute();
            case NOT_FOUND -> settings.notFoundPerMinute();
            case WRITE -> settings.writePerMinute();
            case ABUSE_REPORT -> settings.abuseReportPerMinute();
            case SIGN_IN -> settings.signInPerMinute();
        };
    }

    /**
     * Whole seconds, and never zero: {@code Retry-After: 0} tells a client to come
     * straight back, which turns the limiter into a busy-loop amplifier.
     */
    private static Duration retryAfter(long retryMillis) {
        long seconds = Math.max(1L, (Math.max(0L, retryMillis) + 999L) / 1000L);
        return Duration.ofSeconds(seconds);
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> loaded = new DefaultRedisScript<>();
        loaded.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/token_bucket.lua")));
        loaded.setResultType(List.class);
        return loaded;
    }
}
