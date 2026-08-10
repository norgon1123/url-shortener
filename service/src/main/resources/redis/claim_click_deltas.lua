-- Atomically takes a batch of un-flushed click deltas out of the pending hash.
--
-- Claiming and removing have to be one operation. Every instance runs the flush
-- schedule, so two of them can otherwise read the same delta, both add it to the
-- durable total, and leave the count permanently too high. A Lua script runs to
-- completion with nothing interleaved, so whatever this returns has already been
-- removed and no second flusher can see it.
--
-- The caller owns what it is handed: if the durable write fails it must put the
-- deltas back, or those clicks are lost.
--
-- KEYS[1]  the pending-deltas hash
-- ARGV[1]  how many links to claim in this pass
--
-- Returns a flat { field, value, field, value, ... }

local key = KEYS[1]
local limit = tonumber(ARGV[1])

local pending = redis.call('HGETALL', key)
local claimed = {}
local taken = 0

for i = 1, #pending, 2 do
  if taken >= limit then break end

  local field = pending[i]
  local value = tonumber(pending[i + 1])

  -- Zero-valued fields are swept as we pass: they are the residue of earlier
  -- flushes and would otherwise accumulate one entry per link ever clicked.
  redis.call('HDEL', key, field)

  if value and value > 0 then
    claimed[#claimed + 1] = field
    claimed[#claimed + 1] = tostring(value)
    taken = taken + 1
  end
end

return claimed
