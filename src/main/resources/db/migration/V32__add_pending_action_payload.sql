-- 待确认动作的结构化参数。
-- 之前只有 target_id 单参数，无法表达「目标 + 期望值」这类多参数动作
-- （例如把某个用户设为某个角色）。新增 payload 后保持 target_id 语义不变。
ALTER TABLE IF EXISTS maintenance_pending_actions
    ADD COLUMN IF NOT EXISTS payload varchar(1000);

COMMENT ON COLUMN maintenance_pending_actions.payload IS '待确认动作的结构化参数，可为空';
