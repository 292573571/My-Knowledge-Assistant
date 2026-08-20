CREATE TABLE IF NOT EXISTS email_verification_codes (
    id bigserial PRIMARY KEY,
    email varchar(320) NOT NULL UNIQUE,
    code_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    last_sent_at timestamptz NOT NULL,
    last_sent_ip varchar(64) NOT NULL,
    failed_attempts integer NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_email_verification_codes_ip_sent
    ON email_verification_codes (last_sent_ip, last_sent_at);

CREATE TABLE IF NOT EXISTS email_verification_sends (
    id bigserial PRIMARY KEY,
    email varchar(320) NOT NULL,
    ip_address varchar(64) NOT NULL,
    sent_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_email_verification_sends_ip_sent
    ON email_verification_sends (ip_address, sent_at);
