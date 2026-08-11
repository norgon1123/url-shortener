-- Reads a batch of un-flushed click deltas. Nothing is taken out, and that is
-- the whole point of the script.
--
-- A destructive claim is delivered to Redis whether or not its reply reaches
-- us. A server that is merely slow -- paused, swapping, failing over -- runs
-- the removal after the client has given up waiting, and the clicks it handed
-- back then exist nowhere at all: not in Redis, because they were deleted, and
-- not in PostgreSQL, because nobody ever received them to write them down. That
-- is an unrecoverable undercount produced by a timeout, which AC3 does not
-- allow and AC20 makes likely.
--
-- So the flush copies rather than moves, and subtracts what it wrote once the
-- durable write has committed (RedisClickCounter#settle). Re-reading a delta
-- costs a repeated write; losing one cannot be undone.
--
-- The batch is still bounded and still single-key, so this remains one atomic
-- operation against one hash and survives the shared Redis turning out to be a
-- Cluster.
--
-- Settled fields are swept as we pass, so the hash does not keep one permanent
-- entry per link ever clicked. Only an exact zero goes: a negative field is a
-- correction owed to the durable total and is flushed like any other delta.
--
-- KEYS[1]  the pending-deltas hash
-- ARGV[1]  how many links to read in this pass
--
-- Returns a flat { field, value, field, value, ... }

local key = KEYS[1]
local limit = tonumber(ARGV[1])

local pending = redis.call('HGETALL', key)
local batch = {}
local taken = 0

for i = 1, #pending, 2 do
  if taken >= limit then break end

  local field = pending[i]
  local value = tonumber(pending[i + 1])

  if value == 0 then
    redis.call('HDEL', key, field)
  elseif value then
    batch[#batch + 1] = field
    batch[#batch + 1] = tostring(value)
    taken = taken + 1
  end
end

return batch
