-- MCP 客户端长期访问凭证表：为 MCP Server(/mcp) 提供可吊销的长期 API Key。
-- 明文 key 仅在创建时返回一次，库里只存 SHA-256 哈希；key_prefix 用于界面辨识。
CREATE TABLE IF NOT EXISTS mcp_api_keys (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL,
    name varchar(100) NOT NULL,
    key_prefix varchar(24) NOT NULL,
    key_hash varchar(64) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    expires_at timestamptz,
    last_used_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_mcp_api_keys_key_hash
    ON mcp_api_keys (key_hash);

CREATE INDEX IF NOT EXISTS idx_mcp_api_keys_user_created
    ON mcp_api_keys (user_id, created_at DESC);

COMMENT ON TABLE mcp_api_keys IS 'MCP 客户端长期访问凭证表';
COMMENT ON COLUMN mcp_api_keys.id IS '凭证主键';
COMMENT ON COLUMN mcp_api_keys.user_id IS '所属用户主键';
COMMENT ON COLUMN mcp_api_keys.name IS '凭证名称(便于用户辨识用途)';
COMMENT ON COLUMN mcp_api_keys.key_prefix IS '明文凭证前缀(仅用于展示)';
COMMENT ON COLUMN mcp_api_keys.key_hash IS '凭证明文哈希(SHA-256)';
COMMENT ON COLUMN mcp_api_keys.enabled IS '是否启用';
COMMENT ON COLUMN mcp_api_keys.expires_at IS '过期时间(为空表示长期有效)';
COMMENT ON COLUMN mcp_api_keys.last_used_at IS '最近使用时间';
COMMENT ON COLUMN mcp_api_keys.revoked_at IS '吊销时间(非空即已吊销)';
COMMENT ON COLUMN mcp_api_keys.created_at IS '创建时间';
