-- Per-account sign-in lockout (audit M2). The per-address limit alone lets a
-- password be guessed from many addresses; after ten wrong passwords in a row
-- the account waits fifteen minutes (AuthService), and a successful sign-in or
-- a password reset clears the count.
ALTER TABLE users ADD COLUMN failed_logins INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMPTZ;
