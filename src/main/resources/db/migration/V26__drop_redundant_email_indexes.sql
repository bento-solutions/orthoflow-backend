-- AUDIT-2026-09 L5: both patients.email and users.email are declared UNIQUE,
-- which already creates a btree index. The extra plain indexes added
-- alongside them (V2, V9) are pure duplicates — every insert/update on those
-- tables maintains two identical structures. Drop the duplicates; the
-- unique-constraint indexes remain and still serve equality lookups.
DROP INDEX IF EXISTS idx_patients_email;
DROP INDEX IF EXISTS idx_users_email;
