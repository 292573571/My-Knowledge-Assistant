ALTER TABLE ai_models ADD COLUMN owner_public_id VARCHAR(32);

COMMENT ON COLUMN ai_models.owner_public_id IS '创建者对外公开标识；为空表示系统模型，仅超级管理员可管理';
