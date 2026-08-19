CREATE TABLE system_log (
    id          BIGSERIAL PRIMARY KEY,
    timestamp   TIMESTAMP NOT NULL,
    level       VARCHAR(8) NOT NULL,
    logger      VARCHAR(200) NOT NULL,
    thread      VARCHAR(80) NOT NULL,
    message     TEXT NOT NULL
);

CREATE INDEX idx_system_log_timestamp ON system_log (timestamp DESC);
CREATE INDEX idx_system_log_level ON system_log (level);
