-- Token bucket, one Redis key per (bucket, caller).
--
-- Hand-rolled rather than pulled from a library: no second supply-chain approval
-- on a frozen pom, and because every call here touches exactly one key it keeps
-- working unchanged if the shared Redis turns out to be a Cluster.
--
-- The clock is Redis's own (TIME) rather than the caller's. Callers are many and
-- their clocks disagree; a bucket driven by a skewed client clock either refills
-- early or never refills at all, and neither failure is visible from outside.
--
-- KEYS[1]  bucket key
-- ARGV[1]  capacity, in tokens; also the number of tokens restored per window
-- ARGV[2]  window, in milliseconds
--
-- Returns { allowed, whole tokens remaining, milliseconds until one token }

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local window_millis = tonumber(ARGV[2])

local time = redis.call('TIME')
local now_millis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local state = redis.call('HMGET', key, 'tokens', 'updated')
local tokens = tonumber(state[1])
local updated = tonumber(state[2])

if tokens == nil or updated == nil then
  tokens = capacity
  updated = now_millis
end

-- Continuous refill, so a caller is not held to a fixed window boundary that
-- lets twice the limit through either side of it.
local elapsed = now_millis - updated
if elapsed > 0 then
  tokens = math.min(capacity, tokens + (elapsed * capacity) / window_millis)
  updated = now_millis
end

local allowed = 0
local retry_millis = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry_millis = math.ceil(((1 - tokens) * window_millis) / capacity)
end

redis.call('HSET', key, 'tokens', tokens, 'updated', updated)
-- Two windows is long enough that a full bucket is never charged for an idle
-- caller's history, and short enough that abandoned keys do not accumulate.
redis.call('PEXPIRE', key, window_millis * 2)

return { allowed, math.floor(tokens), retry_millis }
