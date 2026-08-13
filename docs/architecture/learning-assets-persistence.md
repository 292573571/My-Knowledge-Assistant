# 学习资产持久化架构

本文说明普通 AI 助手与 Teaching Agent 如何共享学习资产，以及 PostgreSQL、Markdown 和 RAG 检索副本之间的职责边界。

## 设计目标

- PostgreSQL 保存可查询、可审计、可恢复的业务事实。
- 普通问答、Teaching 讲解、CHECK 和 PRACTICE 使用统一的 `learning_records` 资产模型。
- 正式笔记独立保存为 `formal_notes`，只有经过整理的内容进入 RAG。
- Markdown、文档索引、分块和 Chroma 都可以从数据库事实重建。
- 文件、索引或向量服务故障不会撤销已经提交的学习事实。
- 所有业务读取和投影都保留用户、workspace 隔离信息。

## 数据分层

```text
PostgreSQL 事实层
├── chat_conversations / chat_messages
├── learning_records
├── formal_notes
├── teaching_attempts
├── maintenance_pending_actions
└── outbox / learning_record_outbox / formal_note_outbox

派生投影层
├── docs/learning-records/       可读学习记录导出
├── docs/manual-notes/           可读正式笔记导出
├── document_indexes             文档索引元数据
├── document_chunks              PostgreSQL 稀疏检索分块
└── Chroma                       向量检索副本
```

### 事实表

`learning_records` 保存以下类型：

| `record_type` | 来源 | 是否默认进入 RAG |
| --- | --- | --- |
| `CHAT` | 普通工作台问答 | 否 |
| `TEACHING_EXPLANATION` | Teaching Agent 讲解 | 否 |
| `TEACHING_CHECK` | 教学检查和评分 | 否 |
| `TEACHING_PRACTICE` | 教学实践和评分 | 否 |
| `LEGACY` | 历史 Markdown 导入 | 否 |

每条记录包含 `owner_user_id`、`workspace_id`、日期、主题、来源快照、Markdown 快照和 `source_key`。`source_key` 用于幂等写入；普通聊天使用用户、workspace、日期和问题规范化后的 SHA-256，Teaching 流程使用 attempt、practice 或 session 标识。

`formal_notes` 保存正式笔记正文、内容哈希、兼容文件名/路径和 `index_status`。正式笔记与学习记录是两个不同的事实实体，正式笔记不是把某个 Markdown 文件重新当作事实源。

## 写入流程

### 普通问答

```text
用户问题
  -> 保存 chat_messages
  -> 模型回答成功且 assistant message 保存成功
  -> 写入 learning_records(type=CHAT)
  -> 同一事务写入 learning_record_outbox
  -> 异步生成学习记录 Markdown
```

如果会话在模型生成期间被停止或删除，迟到的助手回答不会保存，也不会生成学习记录。

### Teaching Agent

```text
EXPLAIN  -> learning_records(TEACHING_EXPLANATION)
CHECK    -> teaching_attempts + learning_records(TEACHING_CHECK)
PRACTICE -> teaching_attempts + learning_records(TEACHING_PRACTICE)
```

`teaching_attempts` 保存短期状态机和继续流程所需的状态，`learning_records` 保存长期学习资产。二者不能互相替代。

### 提升正式笔记

```text
学习记录列表/用户编辑内容
  -> 保存 formal_notes
  -> 同一事务写入 formal_note_outbox
  -> formal-note-projection 调度任务 claim 事件
  -> 写入 docs/manual-notes Markdown
  -> 更新 document_indexes、document_chunks、Chroma
  -> 标记 formal_notes.index_status=INDEXED
```

提升接口成功只代表事实表和 Outbox 已提交，不代表文件或向量投影已经完成。投影失败会把正式笔记标记为 `FAILED`，并保留 Outbox 重试信息。

## Outbox 与调度

业务事实和 Outbox 事件必须位于同一个数据库事务中。worker 的处理边界如下：

1. 在短事务中使用 `FOR UPDATE SKIP LOCKED` 抢占一个 `QUEUED` 事件，或抢占租约已过期的 `PROCESSING` 事件。
2. 提交 claim 后，在事务外执行文件写入和文档索引/向量投影，避免长时间持有数据库锁。
3. 成功时用独立事务标记 `DONE`。
4. 失败时用独立事务写入下一次 `available_at`、重试次数和错误类型。

`DatabaseScheduledJobRunner` 只负责高频唤醒、向 `scheduled_jobs` 抢占任务和释放任务租约。真正的执行周期、启用状态和失败计数由 PostgreSQL 管理。多个应用实例可以同时运行，但同一个 Outbox 事件只应由一个实例获得租约。

## 隔离规则

- `workspace_id` 是学习记录和正式笔记的业务隔离字段。
- API 先通过 `WorkspaceService` 校验用户权限，再进入学习记录服务。
- 编辑 Markdown 中的 `知识空间` 标记不能改变当前请求的 workspace 归属。
- 学习记录投影按用户、workspace、日期生成文件，避免不同空间聚合到同一投影。
- 正式笔记投影显式使用数据库中的 owner/workspace 元数据，而不是信任路径名称。
- RAG 查询必须依据文档索引和分块元数据执行可见性过滤。

历史导入器将旧文件作为 `LEGACY` 记录导入。历史内容没有明确 workspace 时保持 `workspace_id IS NULL`，不会自动归属到当前空间。

## 重建和恢复

业务恢复顺序：

1. 恢复 PostgreSQL 事实表和 Outbox 表。
2. 重新运行学习记录投影，恢复可读 Markdown。
3. 重新运行正式笔记投影，恢复 Markdown、`document_indexes`、`document_chunks` 和 Chroma。
4. 对仍为 `FAILED` 的事件检查错误原因并重试。

因此不应把 `docs/learning-records/` 或 `docs/manual-notes/` 当作唯一备份。生产部署还需要为 PostgreSQL、文件投影目录和 Chroma 分别设计持久化和备份策略。

## 当前限制

- V5/V6 Flyway 迁移、JPA 映射、PostgreSQL claim 和多实例抢占尚未完成真实 PostgreSQL 集成验证。
- 当前文件投影仍依赖应用工作区路径；生产应改为持久化卷或对象存储抽象。
- 正式笔记投影已接入统一入口，但索引恢复和 Chroma 失败重试仍需要端到端测试。
- `document_chunks` 和 Chroma 是派生副本，不应直接作为学习资产业务查询接口的数据来源。
