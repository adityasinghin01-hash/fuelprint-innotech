-- Alerts: the contact table. Owner: alerts. AGREEMENT ground rule 10 and section 13.
-- This is the ONLY place a full phone number (E.164) exists. It is not a record: rows are deleted
-- when a sign-up is removed, at local midnight, on "delete all phone numbers" and at start-up for
-- expired sign-ups. Records only carry phone_masked.
CREATE TABLE alert_contact (
    subscription_id TEXT PRIMARY KEY,
    phone_e164      TEXT NOT NULL UNIQUE,
    -- The session that signed up; only this session (or a presenter) may remove the sign-up.
    owner_session_id TEXT NOT NULL,
    created_utc     TEXT NOT NULL
);
