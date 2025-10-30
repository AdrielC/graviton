-- placeholder migration for graviton postgres backend
CREATE TABLE IF NOT EXISTS objects (
  locator TEXT PRIMARY KEY,
  bytes   BYTEA NOT NULL
);

CREATE TABLE IF NOT EXISTS kv (
  key   TEXT PRIMARY KEY,
  value BYTEA NOT NULL
);
