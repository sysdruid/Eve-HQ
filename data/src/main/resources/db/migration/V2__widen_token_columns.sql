-- An access token carries every granted scope, so with all ESI scopes it exceeds 4096 characters, and H2
-- truncates silently instead of failing. Encrypted values add a further third on top.
ALTER TABLE characters ALTER COLUMN access_token SET DATA TYPE VARCHAR(16384);
ALTER TABLE characters ALTER COLUMN refresh_token SET DATA TYPE VARCHAR(2048);
