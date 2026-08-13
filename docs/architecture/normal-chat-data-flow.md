# 普通问答数据流

本文描述前端通过普通 `POST /api/workbench/chat` 发起一次问答时，消息、知识库检索、聊天历史和学习记录的完整流转。

```mermaid
sequenceDiagram
    autonumber
    actor User as 用户
    participant Browser as Vue 浏览器
    participant API as WorkbenchStreamController
    participant Chat as WorkbenchChatService
    participant Conversation as ConversationService
    participant DB as PostgreSQL
    participant RAG as RagService
    participant Memory as ConversationMemory
    participant Vector as VectorStore / Chroma
    participant Model as LLM
    participant Learning as LearningRecordService
    participant LearningOutbox as learning_record_outbox
    participant Scheduler as DatabaseScheduledJobRunner
    participant Docs as Markdown 投影

    User->>Browser: 输入问题并发送
    Browser->>API: POST /api/workbench/chat<br/>Bearer Token + conversationId + message
    API->>Chat: chat(当前用户, 请求)
    Chat->>Chat: 创建本次生成 Execution<br/>用于停止/删除时取消

    Chat->>Conversation: recordUserMessage(...)
    Conversation->>DB: 创建或更新 chat_conversations
    Conversation->>DB: 插入 user chat_messages

    Chat->>RAG: chat(用户范围会话 ID, question)
    RAG->>Memory: 读取最近对话上下文
    RAG->>RAG: 查询改写 / 多查询（启用时）
    RAG->>Vector: similaritySearch(查询, Top-K)
    Vector-->>RAG: 候选文档分块与分数
    RAG->>RAG: 阈值过滤、规则重排、构建上下文

    alt 命中足够的本地知识库内容
        RAG->>Model: 提示词 = 系统提示 + 历史 + 文档上下文 + 问题
        Model-->>RAG: 基于上下文的回答
        RAG->>RAG: AnswerJudge 校验回答是否有上下文依据
        alt 缺少依据
            RAG->>RAG: 以原始文档片段生成保守回答
        end
        RAG->>RAG: 生成结构化引用并追加参考来源
    else 未命中或本地证据不足
        RAG->>Model: 通用知识回退提示词
        Model-->>RAG: 通用模型回答
        RAG->>RAG: 标记“不是当前知识库内容”
    end

    RAG->>Memory: 写入用户问题和助手回答
    RAG-->>Chat: answer + sources + retrievalDebug

    alt 会话在模型生成期间已停止或删除
        Chat->>Memory: 清理该会话短期记忆
        Chat-->>API: 丢弃迟到结果，不写入数据库和学习记录
        API-->>Browser: 返回结果（前端已停止时不再使用）
    else 会话仍有效
        Chat->>Conversation: recordAssistantMessage(...)
        Conversation->>DB: 更新 chat_conversations.updated_at
        Conversation->>DB: 插入 assistant chat_messages<br/>保存回答、来源和工具元数据

        Chat->>Learning: record(用户, workspaceId, 问题, 回答, 来源)
        Learning->>DB: UPSERT learning_records
        Learning->>LearningOutbox: 同一事务写入投影事件
        Learning-->>Chat: 事实提交成功
        Scheduler->>LearningOutbox: claim<br/>FOR UPDATE SKIP LOCKED
        LearningOutbox-->>Scheduler: 用户、workspace、日期和租约
        Scheduler->>Docs: 生成 Markdown 可读投影
        Docs-->>LearningOutbox: 完成或记录重试状态

        Chat-->>API: WorkbenchChatResponse
        API-->>Browser: answer + sources + messageId
        Browser->>Browser: 更新助手气泡、引用和本地会话状态
    end
```

## 关键边界

1. 用户消息先于模型调用保存，因此模型异常时问题仍可出现在聊天历史中。
2. RAG 回答优先使用本地知识库；无本地来源或资料不足时，才使用通用模型回退，并明确标记来源边界。
3. `ConversationExecutionRegistry` 防止停止或删除会话后，模型迟到结果重新写入 PostgreSQL。
4. 学习记录只在助手消息成功持久化后创建，因此被取消或删除的对话不会沉淀为知识。
5. `learning_records` 是学习记录事实源；普通问答成功写入后只保存结构化记录和 Outbox，不在请求线程中写文件或同步重建索引。
6. 学习记录投影 worker 按用户、workspace、日期生成 Markdown。文件是可读导出物，可以在事实表存在时删除后重建，不是权限或业务状态来源。
7. 正式笔记提升先写入 `formal_notes` 和 `formal_note_outbox`。正式笔记 worker 再生成 Markdown，并通过显式 owner/workspace 元数据更新文档索引、分块和向量库；投影失败不会撤销已提交的正式笔记。
8. 旧学习记录导入为 `LEGACY`，保留空 workspace。向量检索的用户和 workspace 过滤必须使用索引元数据和查询条件，不能仅将目录结构视为完整的数据访问隔离。

## 关键数据落点

| 数据 | 存储位置 | 用途 |
| --- | --- | --- |
| 用户与会话 | PostgreSQL `app_users`、`chat_conversations` | 身份与会话列表 |
| 聊天消息 | PostgreSQL `chat_messages` | 恢复历史消息、来源和工具事件 |
| 短期对话上下文 | `ConversationMemory` | 为当前 RAG 请求补充最近上下文 |
| 原始知识文档 | `docs/` | 可重新索引的知识源 |
| 学习记录事实 | PostgreSQL `learning_records` | 普通问答、Teaching 讲解、CHECK、PRACTICE 和历史导入记录 |
| 学习记录事件 | PostgreSQL `learning_record_outbox` | Markdown 投影的租约、重试和完成状态 |
| 每日学习记录投影 | `docs/learning-records/user-<id>/<workspace>/YYYY-MM-DD.md` | 可读导出物，不是事实源 |
| 正式笔记事实 | PostgreSQL `formal_notes` | 用户或 workspace 的整理笔记正文、哈希和索引状态 |
| 正式笔记事件 | PostgreSQL `formal_note_outbox` | Markdown、文档索引、分块和向量投影事件 |
| 正式笔记投影 | `docs/manual-notes/user-<id>/<workspace>/YYYY-MM-DD-learning-note.md` | 可重建文件投影 |
| 文档索引 | `DocumentIndexStore` | 文件路径、内容哈希、分块数等索引元数据 |
| 文档向量 | Chroma / `VectorStore` | 相似度召回 |
