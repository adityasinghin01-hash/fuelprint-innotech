-- Core tables: records (append-only revisions), commands (one stored receipt per command_id)
-- and events (the live cursor). Owner: core. AGREEMENT sections 3 and 6 (version 2).
-- Nothing in these three tables is ever updated or deleted; triggers refuse it.

-- Every saved record revision. A change is a NEW row with revision + 1.
-- run_id is NULL for records not tied to a run (model_version, alert_subscription,
-- notification, automation_settings).
CREATE TABLE records (
    row_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    type        TEXT    NOT NULL,
    id          TEXT    NOT NULL,
    revision    INTEGER NOT NULL CHECK (revision >= 1),
    run_id      TEXT,
    created_utc TEXT    NOT NULL,
    created_by  TEXT    NOT NULL,
    command_id  TEXT REFERENCES commands (command_id) DEFERRABLE INITIALLY DEFERRED,
    body        TEXT    NOT NULL,
    UNIQUE (type, id, revision)
);

CREATE INDEX records_type_run ON records (type, run_id);

-- The latest revision of every record. first_row_id keeps the order in which records were first made.
CREATE VIEW latest_records AS
SELECT r.row_id, r.type, r.id, r.revision, r.run_id, r.created_utc, r.created_by, r.command_id, r.body,
       (SELECT f.row_id FROM records f WHERE f.type = r.type AND f.id = r.id AND f.revision = 1) AS first_row_id
FROM records r
WHERE r.revision = (SELECT MAX(m.revision) FROM records m WHERE m.type = r.type AND m.id = r.id);

CREATE TRIGGER records_no_update BEFORE UPDATE ON records
BEGIN
    SELECT RAISE(ABORT, 'records are append-only: save a new revision');
END;

CREATE TRIGGER records_no_delete BEFORE DELETE ON records
BEGIN
    SELECT RAISE(ABORT, 'records are append-only: rows are never deleted');
END;

-- A new revision must be exactly the latest revision + 1 (1 for a new record).
CREATE TRIGGER records_revision_follows BEFORE INSERT ON records
WHEN NEW.revision <> COALESCE((SELECT MAX(revision) FROM records WHERE type = NEW.type AND id = NEW.id), 0) + 1
BEGIN
    SELECT RAISE(ABORT, 'revision must follow the latest revision');
END;

-- A record never moves to another run (NULL-safe comparison).
CREATE TRIGGER records_run_fixed BEFORE INSERT ON records
WHEN EXISTS (SELECT 1 FROM records WHERE type = NEW.type AND id = NEW.id AND run_id IS NOT NEW.run_id)
BEGIN
    SELECT RAISE(ABORT, 'a record cannot move to another run');
END;

-- Every command the bus decided on (APPLIED or REJECTED), with the exact receipt it returned.
-- Sending the same command_id again returns receipt_json and changes nothing.
-- payload has the handler's private fields (for example a phone number) replaced; see CommandHandler.
CREATE TABLE commands (
    row_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    command_id        TEXT    NOT NULL UNIQUE,
    command_type      TEXT    NOT NULL,
    actor             TEXT    NOT NULL,
    automatic         INTEGER NOT NULL CHECK (automatic IN (0, 1)),
    run_id            TEXT,
    expected_revision INTEGER,
    payload           TEXT    NOT NULL,
    request_digest    TEXT    NOT NULL,
    status            TEXT    NOT NULL,
    reason_code       TEXT,
    receipt_json      TEXT    NOT NULL,
    received_utc      TEXT    NOT NULL,
    -- Only the server's own automation actor is automatic, and it always is.
    CHECK ((actor = 'automation') = (automatic = 1))
);

-- The automation log: receipts with automatic = 1 in time order.
CREATE INDEX commands_automatic ON commands (automatic, row_id);

CREATE TRIGGER commands_no_update BEFORE UPDATE ON commands
BEGIN
    SELECT RAISE(ABORT, 'commands are append-only');
END;

CREATE TRIGGER commands_no_delete BEFORE DELETE ON commands
BEGIN
    SELECT RAISE(ABORT, 'commands are append-only');
END;

-- One event per saved record revision. cursor only goes up and is never reused (AUTOINCREMENT).
CREATE TABLE events (
    cursor      INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type  TEXT    NOT NULL,
    record_type TEXT    NOT NULL,
    record_id   TEXT    NOT NULL,
    revision    INTEGER NOT NULL,
    run_id      TEXT,
    sim_time_s  REAL,
    wall_utc    TEXT    NOT NULL,
    actor       TEXT    NOT NULL,
    command_id  TEXT REFERENCES commands (command_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (record_type, record_id, revision) REFERENCES records (type, id, revision)
);

CREATE INDEX events_run_cursor ON events (run_id, cursor);
CREATE INDEX events_record ON events (record_type, record_id, revision);

CREATE TRIGGER events_no_update BEFORE UPDATE ON events
BEGIN
    SELECT RAISE(ABORT, 'events are append-only');
END;

CREATE TRIGGER events_no_delete BEFORE DELETE ON events
BEGIN
    SELECT RAISE(ABORT, 'events are append-only');
END;
