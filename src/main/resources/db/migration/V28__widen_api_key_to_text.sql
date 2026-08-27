-- API Key 改为透明加密存储(AES-GCM + base64),密文可能超过原 varchar(256),扩为 text。
ALTER TABLE ai_models ALTER COLUMN api_key TYPE text;
ALTER TABLE user_model_config ALTER COLUMN api_key TYPE text;
