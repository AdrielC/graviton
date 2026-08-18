-- Apply ownership + grants after the authoritative schema is installed.
-- This runs after 10_schema.sql (lexicographic ordering in docker-entrypoint-initdb.d).

\connect quasar

-- Ownership: let the migrations role own the schemas.
ALTER SCHEMA core OWNER TO quasar_migrate;
ALTER SCHEMA graviton OWNER TO quasar_migrate;
ALTER SCHEMA quasar OWNER TO quasar_migrate;

-- App role needs to use schemas.
GRANT USAGE ON SCHEMA core TO quasar_app;
GRANT USAGE ON SCHEMA graviton TO quasar_app;
GRANT USAGE ON SCHEMA quasar TO quasar_app;

-- Default privileges for future objects created by migrations role.
ALTER DEFAULT PRIVILEGES FOR ROLE quasar_migrate IN SCHEMA core
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO quasar_app;
ALTER DEFAULT PRIVILEGES FOR ROLE quasar_migrate IN SCHEMA graviton
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO quasar_app;
ALTER DEFAULT PRIVILEGES FOR ROLE quasar_migrate IN SCHEMA quasar
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO quasar_app;

ALTER DEFAULT PRIVILEGES FOR ROLE quasar_migrate IN SCHEMA core
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO quasar_app;
ALTER DEFAULT PRIVILEGES FOR ROLE quasar_migrate IN SCHEMA graviton
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO quasar_app;
ALTER DEFAULT PRIVILEGES FOR ROLE quasar_migrate IN SCHEMA quasar
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO quasar_app;

-- Existing tables: grant access (covers the initial schema file).
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core TO quasar_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA graviton TO quasar_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA quasar TO quasar_app;

-- RLS enforcement: the app role MUST NOT be able to bypass row-level
-- security policies, otherwise a buggy query would leak cross-tenant
-- data. The migration role (which owns tables) retains BYPASSRLS so
-- it can run maintenance / reporting that intentionally spans tenants.
ALTER ROLE quasar_app      NOBYPASSRLS;
ALTER ROLE quasar_migrate  BYPASSRLS;

-- Make the RLS policies strict: without an explicit USING clause, a
-- policy that is enabled but not bound behaves as deny-by-default when
-- the `app.org_id` GUC is unset. Force this by setting the default
-- for newly created tables too.
ALTER DEFAULT PRIVILEGES FOR ROLE quasar_migrate IN SCHEMA quasar
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO quasar_app;

