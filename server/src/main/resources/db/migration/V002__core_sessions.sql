-- Login sessions (CORE-API section 2). Not records: a session is access control, not world state.
-- Only a SHA-256 of the cookie secret is stored; the secret itself is never saved or logged.
CREATE TABLE sessions (
    token_hash  TEXT PRIMARY KEY,
    kind        TEXT    NOT NULL CHECK (kind IN ('STATION', 'VISITOR', 'GUEST')),
    actor_label TEXT    NOT NULL CHECK (lower(trim(actor_label)) NOT IN ('automation', 'anonymous')),
    station_id  TEXT,
    roles       TEXT    NOT NULL CHECK (roles <> '' AND ',' || roles || ',' NOT LIKE '%,automation,%'),
    created_utc TEXT    NOT NULL,
    expires_ms  INTEGER NOT NULL,
    revoked     INTEGER NOT NULL DEFAULT 0 CHECK (revoked IN (0, 1))
);

CREATE INDEX sessions_expires ON sessions (expires_ms);
