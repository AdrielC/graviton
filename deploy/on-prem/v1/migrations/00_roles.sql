-- Quasar v1 on-prem bootstrap roles.
--
-- Note: executed by the official Postgres container during first init.
-- This is intentionally boring: roles, db grants, and a minimal search_path.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'quasar_app') THEN
    CREATE ROLE quasar_app LOGIN PASSWORD 'quasar_app';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'quasar_migrate') THEN
    CREATE ROLE quasar_migrate LOGIN PASSWORD 'quasar_migrate';
  END IF;
END $$;

-- Least-privilege defaults: schema owner is migrations role, app role can read/write.
ALTER DATABASE quasar OWNER TO quasar_migrate;

\connect quasar

GRANT CONNECT ON DATABASE quasar TO quasar_app;
GRANT CONNECT ON DATABASE quasar TO quasar_migrate;

-- When the DDL creates schemas/tables, ensure app role can use them.
ALTER ROLE quasar_app SET search_path = quasar, graviton, core, public;
ALTER ROLE quasar_migrate SET search_path = quasar, graviton, core, public;

