ALTER TABLE system_log ADD COLUMN request_id varchar(128);
ALTER TABLE system_log ADD COLUMN trace_id varchar(128);
ALTER TABLE system_log ADD COLUMN user_id varchar(64);
ALTER TABLE system_log ADD COLUMN workspace_id varchar(120);
ALTER TABLE system_log ADD COLUMN instance_id varchar(128);
ALTER TABLE system_log ADD COLUMN environment varchar(32);
ALTER TABLE system_log ADD COLUMN exception_type varchar(255);
ALTER TABLE system_log ADD COLUMN stack_trace text;

CREATE INDEX idx_system_log_request_id ON system_log (request_id);
CREATE INDEX idx_system_log_trace_id ON system_log (trace_id);
CREATE INDEX idx_system_log_user_id ON system_log (user_id);
CREATE INDEX idx_system_log_workspace_id ON system_log (workspace_id);
