-- A role for the application to connect as, with no more than it needs (audit M3).
--
-- The application connected as the database superuser, so a flaw anywhere in it
-- could drop the audit log's append-only trigger, read other databases on the
-- server, or rewrite the schema. orthoflow_app may read and write rows and use
-- sequences, and nothing else: no DDL, so it cannot touch the trigger
-- (V65__audit_log.sql) or the tables themselves. Migrations keep running as the
-- owner (spring.flyway.user).
--
-- The role cannot log in. An operator gives the application a login of its own:
--   CREATE ROLE orthoflow_app_login LOGIN PASSWORD '…' IN ROLE orthoflow_app;
-- and sets DB_APP_USER / DB_APP_PASSWORD (see .env.example). Until then the
-- application keeps the owner's connection, as before.
--
-- A deployment whose migration user may not create roles skips this with a
-- notice rather than failing to start; it then has to create the role by hand.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'orthoflow_app') THEN
        CREATE ROLE orthoflow_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO orthoflow_app', current_database());
    GRANT USAGE ON SCHEMA public TO orthoflow_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO orthoflow_app;
    GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO orthoflow_app;
    GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO orthoflow_app;
    -- Tables, sequences and functions the owner creates in later migrations.
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO orthoflow_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO orthoflow_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO orthoflow_app;
    -- Flyway's own history is the owner's business.
    REVOKE INSERT, UPDATE, DELETE ON flyway_schema_history FROM orthoflow_app;
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE NOTICE 'orthoflow_app was not created (the migration user may not create roles); create it by hand, see V74';
END
$$;
