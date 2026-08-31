package graviton.integration.redis

private[redis] object RedisAdmissionScripts:

  /**
   * One static script keeps every key in a single Redis Cluster hash slot and
   * makes acquire, renew, release, expiry reaping, snapshots, policy changes,
   * and their event records atomic. Values are plain RESP strings and numbers.
   */
  val Coordinator: String =
    """
local counts = KEYS[1]
local expirations = KEYS[2]
local leases = KEYS[3]
local fence = KEYS[4]
local policy = KEYS[5]
local events = KEYS[6]

local action = ARGV[1]
local max_events = tonumber(ARGV[2])
local reap_limit = tonumber(ARGV[3])

local function now_ms()
  local t = redis.call('TIME')
  return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

local function count(field)
  return tonumber(redis.call('HGET', counts, field) or '0')
end

local function set_count(field, value)
  if value <= 0 then
    redis.call('HDEL', counts, field)
  else
    redis.call('HSET', counts, field, tostring(value))
  end
end

local function increment(field, delta)
  local value = count(field) + delta
  if value < 0 then value = 0 end
  set_count(field, value)
  return value
end

local function parse_record(record)
  local values = {}
  for value in string.gmatch(record, '([^|]+)') do
    table.insert(values, value)
  end
  return values
end

local function occupancy(tenant, backend)
  local tenant_bytes = '-1'
  local tenant_transfers = '-1'
  if tenant ~= '-' then
    tenant_bytes = tostring(count('t:' .. tenant .. ':b'))
    tenant_transfers = tostring(count('t:' .. tenant .. ':c'))
  end
  return {
    tostring(count('s:b')),
    tostring(count('s:c')),
    tenant_bytes,
    tenant_transfers,
    tostring(count('b:' .. backend .. ':c'))
  }
end

local function emit(kind, at, lease, token, tenant, backend, bytes, operation, policy_version, outcome)
  local current = occupancy(tenant, backend)
  redis.call(
    'XADD', events, 'MAXLEN', '~', tostring(max_events), '*',
    'schema', 'graviton-admission-event-v1',
    'kind', kind,
    'at_ms', tostring(at),
    'lease', lease,
    'fencing_token', token,
    'tenant', tenant,
    'backend', backend,
    'bytes', bytes,
    'operation', operation,
    'policy_version', policy_version,
    'outcome', outcome,
    'service_bytes', current[1],
    'service_transfers', current[2],
    'tenant_bytes', current[3],
    'tenant_transfers', current[4],
    'backend_transfers', current[5]
  )
end

local function release_record(id, record, at, kind, outcome)
  local values = parse_record(record)
  local token = values[1]
  local tenant = values[2]
  local backend = values[3]
  local bytes = tonumber(values[4])
  local operation = values[6]
  local policy_version = values[7]

  increment('s:b', -bytes)
  increment('s:c', -1)
  if tenant ~= '-' then
    increment('t:' .. tenant .. ':b', -bytes)
    increment('t:' .. tenant .. ':c', -1)
  end
  increment('b:' .. backend .. ':c', -1)
  redis.call('HDEL', leases, id)
  redis.call('ZREM', expirations, id)
  emit(kind, at, id, token, tenant, backend, tostring(bytes), operation, policy_version, outcome)
end

local function reap(at)
  local expired = redis.call('ZRANGEBYSCORE', expirations, '-inf', tostring(at), 'LIMIT', 0, reap_limit)
  for _, id in ipairs(expired) do
    local record = redis.call('HGET', leases, id)
    if record then release_record(id, record, at, 'expired', 'lease_expired') end
    redis.call('ZREM', expirations, id)
  end
  return #expired
end

local at = now_ms()
reap(at)

if action == 'acquire' then
  local id = ARGV[4]
  local tenant = ARGV[5]
  local backend = ARGV[6]
  local bytes = tonumber(ARGV[7])
  local default_service_bytes = tonumber(ARGV[8])
  local default_service_transfers = tonumber(ARGV[9])
  local default_tenant_bytes = tonumber(ARGV[10])
  local default_tenant_transfers = tonumber(ARGV[11])
  local default_backend_transfers = tonumber(ARGV[12])
  local ttl = tonumber(ARGV[13])
  local retry = ARGV[14]
  local operation = ARGV[15]

  if redis.call('HEXISTS', leases, id) == 1 then return 'PROTOCOL|duplicate_lease_id' end

  local policy_version = tonumber(redis.call('HGET', policy, 'version') or '0')
  local service_bytes_limit = tonumber(redis.call('HGET', policy, 'service:bytes') or tostring(default_service_bytes))
  local service_transfers_limit = tonumber(redis.call('HGET', policy, 'service:transfers') or tostring(default_service_transfers))
  local tenant_bytes_limit = default_tenant_bytes
  local tenant_transfers_limit = default_tenant_transfers
  if tenant ~= '-' then
    tenant_bytes_limit = tonumber(redis.call('HGET', policy, 't:' .. tenant .. ':bytes') or tostring(default_tenant_bytes))
    tenant_transfers_limit = tonumber(redis.call('HGET', policy, 't:' .. tenant .. ':transfers') or tostring(default_tenant_transfers))
  end
  local backend_transfers_limit = tonumber(redis.call('HGET', policy, 'b:' .. backend .. ':transfers') or tostring(default_backend_transfers))

  local current = occupancy(tenant, backend)
  local dimension = nil
  if tonumber(current[1]) + bytes > service_bytes_limit then dimension = 'service_bytes'
  elseif tonumber(current[2]) + 1 > service_transfers_limit then dimension = 'service_transfers'
  elseif tenant ~= '-' and tonumber(current[3]) + bytes > tenant_bytes_limit then dimension = 'tenant_bytes'
  elseif tenant ~= '-' and tonumber(current[4]) + 1 > tenant_transfers_limit then dimension = 'tenant_transfers'
  elseif tonumber(current[5]) + 1 > backend_transfers_limit then dimension = 'backend_transfers'
  end

  if dimension then
    return table.concat({
      'REJECTED', dimension, retry,
      current[1], current[2], current[3], current[4], current[5], tostring(policy_version)
    }, '|')
  end

  local token = redis.call('INCR', fence)
  local expires = at + ttl
  increment('s:b', bytes)
  increment('s:c', 1)
  if tenant ~= '-' then
    increment('t:' .. tenant .. ':b', bytes)
    increment('t:' .. tenant .. ':c', 1)
  end
  increment('b:' .. backend .. ':c', 1)
  local record = table.concat({
    tostring(token), tenant, backend, tostring(bytes), tostring(at), operation, tostring(policy_version)
  }, '|')
  redis.call('HSET', leases, id, record)
  redis.call('ZADD', expirations, tostring(expires), id)
  emit('admitted', at, id, tostring(token), tenant, backend, tostring(bytes), operation, tostring(policy_version), 'admitted')
  local admitted = occupancy(tenant, backend)
  return table.concat({
    'ADMITTED', tostring(token), tostring(expires), tostring(policy_version),
    admitted[1], admitted[2], admitted[3], admitted[4], admitted[5]
  }, '|')
end

if action == 'renew' then
  local id = ARGV[4]
  local token = ARGV[5]
  local ttl = tonumber(ARGV[6])
  local record = redis.call('HGET', leases, id)
  if not record then return 'LOST' end
  local values = parse_record(record)
  if values[1] ~= token then return 'STALE' end
  local expires = at + ttl
  redis.call('ZADD', expirations, tostring(expires), id)
  return 'RENEWED|' .. tostring(expires)
end

if action == 'release' then
  local id = ARGV[4]
  local token = ARGV[5]
  local outcome = ARGV[6]
  local record = redis.call('HGET', leases, id)
  if not record then return 'MISSING' end
  local values = parse_record(record)
  if values[1] ~= token then return 'STALE' end
  release_record(id, record, at, 'completed', outcome)
  return 'RELEASED'
end

if action == 'snapshot' then
  local tenant = ARGV[4]
  local backend = ARGV[5]
  local current = occupancy(tenant, backend)
  local version = redis.call('HGET', policy, 'version') or '0'
  return table.concat({
    'SNAPSHOT', current[1], current[2], current[3], current[4], current[5], version, tostring(at)
  }, '|')
end

if action == 'policy' then
  local tenant = ARGV[4]
  local bytes = ARGV[5]
  local transfers = ARGV[6]
  if bytes == '-' then redis.call('HDEL', policy, 't:' .. tenant .. ':bytes')
  else redis.call('HSET', policy, 't:' .. tenant .. ':bytes', bytes) end
  if transfers == '-' then redis.call('HDEL', policy, 't:' .. tenant .. ':transfers')
  else redis.call('HSET', policy, 't:' .. tenant .. ':transfers', transfers) end
  local version = redis.call('HINCRBY', policy, 'version', 1)
  emit('policy_changed', at, '-', '-', tenant, '-', '0', 'control', tostring(version), 'updated')
  return 'POLICY|' .. tostring(version)
end

if action == 'event' then
  emit(ARGV[4], at, '-', '-', ARGV[5], ARGV[6], ARGV[7], ARGV[8], ARGV[9], ARGV[10])
  return 'RECORDED'
end

if action == 'traffic' then
  local quota_key = KEYS[7]
  local amount = tonumber(ARGV[4])
  local limit = tonumber(ARGV[5])
  local window_ms = tonumber(ARGV[6])
  if not amount or amount <= 0 then return 'PROTOCOL|invalid_traffic_amount' end
  local window = math.floor(at / window_ms)
  local observed_window = tonumber(redis.call('HGET', quota_key, 'window') or '-1')
  local current = 0
  if observed_window == window then
    current = tonumber(redis.call('HGET', quota_key, 'count') or '0')
  end
  if current + amount > limit then
    local remaining = ((window + 1) * window_ms) - at
    if remaining < 1 then remaining = 1 end
    return table.concat({'TRAFFIC_REJECTED', tostring(limit), tostring(remaining)}, '|')
  end
  local updated = current + amount
  redis.call('HSET', quota_key, 'window', tostring(window), 'count', tostring(updated))
  redis.call('PEXPIRE', quota_key, window_ms * 2)
  return 'TRAFFIC_CHARGED|' .. tostring(updated)
end

return 'PROTOCOL|unknown_action'
"""
