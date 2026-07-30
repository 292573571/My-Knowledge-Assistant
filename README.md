# My Knowledge Assistant

一个基于 Spring Boot 和 Spring AI 的知识库助手项目。项目支持多用户知识空间、文档导入、RAG 问答、多轮对话、Chroma 向量数据库、OpenAI-compatible 模型、搜索兜底、答案质量检查和工具调用日志。

## 项目功能

- 读取 `docs` 目录下的 `.md` / `.txt` 文档。
- 将文档切分成 chunks，并保留 `source`、`path`、`chunkIndex`、`title` metadata。
- 使用 Spring AI Embedding 将 chunks 向量化。
- 将向量写入 Chroma collection：`knowledge_assistant`。
- 支持 `/api/rag/chat` 基于知识库问答。
- 支持 `conversationId` 多轮对话。
- RAG 上下文不足时再调用搜索工具，不会每次都搜索。
- 回答后通过 judge 检查是否基于上下文，不合格会重新生成保守答案。
- 记录工具调用日志，包括调用原因和 confidence。

## 架构图

```text
User
  |
  v
ChatController / RagController
  |
  v
TaskRouter
  |
  +--> 知识问题 ----------------> RagService
  |                                |
  |                                +--> VectorStore.similaritySearch
  |                                |      |
  |                                |      +--> ChromaVectorStoreAdapter
  |                                |             |
  |                                |             +--> Chroma
  |                                |             +--> InMemoryVectorStore fallback
  |                                |
  |                                +--> Spring AI ChatClient
  |                                +--> AnswerJudge
  |
  +--> 实时问题 ----------------> WebSearchService
  |                                |
  |                                +--> 搜索结果进入 Prompt
  |                                +--> 回答标明来自 Web
Document Ingestion
  |
  v
POST /api/ingest
  |
  v
DocumentIngestionService
  |
  +--> read docs/*.md docs/*.txt
  +--> split chunks
  +--> metadata
  +--> Spring AI Embedding
  +--> Chroma VectorStore
```

## 本地配置

直接编辑唯一配置文件 `src/main/resources/application.properties`，填写聊天模型、Embedding 模型和服务地址。API Key 不写入项目文件，启动后端前在系统环境或 IntelliJ Run Configuration 中设置：

```text
OPENAI_API_KEY=聊天模型 API Key
OPENAI_EMBEDDING_API_KEY=Embedding API Key
```

IntelliJ 中可在 `Run -> Edit Configurations -> PersonalAiWorkbenchApplication -> Environment variables` 设置这两个值。

```bash
spring.ai.openai.base-url=https://api.siliconflow.cn
spring.ai.openai.chat.options.model=Qwen/Qwen2.5-7B-Instruct
spring.ai.openai.embedding.base-url=https://api.siliconflow.cn
spring.ai.openai.embedding.options.model=BAAI/bge-m3
```

地址不要以 `/v1` 结尾，Spring AI 会自动追加 OpenAI API 路径。

## PostgreSQL 表与字段注释

全部 JPA 实体都使用 Hibernate `@Comment` 声明了中文表注释和中文字段注释，包括主键、普通字段和外键字段。当前 `spring.jpa.hibernate.ddl-auto=update` 会在 PostgreSQL 建表或更新结构时生成对应的 `COMMENT ON TABLE` 和 `COMMENT ON COLUMN` 语句，已有数据库在应用启动执行 schema update 后也会补充注释。

可在 PostgreSQL 中查询注释：

```sql
SELECT obj_description('app_users'::regclass, 'pg_class') AS table_comment;

SELECT
    a.attname AS column_name,
    col_description(a.attrelid, a.attnum) AS column_comment
FROM pg_attribute a
WHERE a.attrelid = 'app_users'::regclass
  AND a.attnum > 0
  AND NOT a.attisdropped
ORDER BY a.attnum;
```

`DatabaseCommentCoverageTest` 会自动扫描项目中的全部 `@Entity`，要求每张表以及每个 `@Id`、`@Column`、`@JoinColumn` 字段都有包含中文的 `@Comment`。后续切换到 Flyway 时，应将相同的 `COMMENT ON` 语句固化到版本化迁移脚本，并在生产环境关闭 `ddl-auto=update`。

## 如何运行

前置要求：

- Java 17
- Maven 3.9+
- Node.js 20+
- Docker

启动 Chroma：

```bash
docker compose up -d chroma
```

启动后端：

```bash
mvn spring-boot:run
```

如果本机提示 `mvn: command not found`，需要先安装 Maven，例如 macOS 可以执行：

```bash
brew install maven
```

启动前端：

```bash
cd frontend
npm install
npm run dev
```

前端默认运行在：

```text
http://localhost:5173
```

Vite 会把 `/api` 请求代理到后端 `http://localhost:8080`。

## 如何测试

运行后端单元测试：

```bash
mvn test
```

当前测试覆盖：

- Markdown 文档切分和 heading metadata。
- Text 文档切分和 chunk index。
- 文档类型路由。
- 文档索引读写、删除、清空。
- RAG 规则评测器。

运行前端构建检查：

```bash
cd frontend
npm run build
```

## 一键 Eval

项目提供 RAG 评测脚本：

```bash
./scripts/run-evals.sh
```

脚本会读取 `src/main/resources/application.properties`，并以 `app.mode=eval` 启动 Spring Boot，执行 `eval/questions.jsonl` 中的用例。运行汇总和每题完整检索结果会保存到：

```text
PostgreSQL: eval_runs、eval_run_results
```

命令行运行记录不绑定 Web 用户；在前端“检索记录”中查看的记录，来自当前登录用户通过评测页面发起的运行。

只跑少量样例：

```bash
./scripts/run-evals.sh --sample
```

开启 LLM judge：

```bash
./scripts/run-evals.sh --judge
```

运行独立 eval 前，除了 `src/main/resources/application.properties` 中的模型和服务地址，还需要在启动脚本的终端中设置 API Key：

```bash
export OPENAI_API_KEY='你的聊天模型 API Key'
export OPENAI_EMBEDDING_API_KEY='你的 Embedding API Key'
./scripts/run-evals.sh
```

如果应用已经运行在 `http://localhost:8080`，直接执行 `./scripts/run-evals.sh` 会复用该实例；此时 API Key 由运行该应用的 IntelliJ Run Configuration 或系统环境提供。

如果使用 IntelliJ，建议修改代码后执行：

```text
Build -> Rebuild Project
```

## 检索评测与题目导入

前端“检索评测”页面提供 Eval Case 题库管理、标准检索/增强检索对比和历史运行查看：

- 评测题保存在 PostgreSQL 的 `eval_cases`，按登录用户隔离。
- 标准检索使用当前全局检索配置；增强检索仅对本次运行开启查询改写和多查询，不修改全局配置。
- 运行结果保存在 `eval_runs` 与 `eval_run_results`，可在“检索记录”中查看标准/增强模式、运行时间、通过率、检索命中率和每题明细。
- 后端接口：

```text
GET  /api/eval/cases
POST /api/eval/cases
PUT  /api/eval/cases/{id}
DELETE /api/eval/cases/{id}
POST /api/eval/run
GET  /api/eval/runs
GET  /api/eval/runs/{runId}
```

### 导入评测题

题库页面的“导入”支持以下格式：

```text
.xlsx
.md
.json
```

- 文件上限为 `5 MB`。
- Excel 与 Markdown 使用首行表头；JSON 支持对象数组或 JSONL。
- 每条题目至少需要“问题”；未填写“类型”时默认使用 `fact`。
- 支持的字段：`模式`、`类型`、`问题`、`期望来源`、`期望标题路径`、`期望关键词`、`禁用关键词`、`期望无回答`、`要求本地证据`、`允许模型兜底`。
- 列表字段可使用逗号、顿号或换行分隔；布尔值支持“是/否”、`true/false`、`1/0`。
- Case ID 始终由服务端生成，导入文件中的 Case ID 不会保留。
- 任一题目无效时，整批导入回滚，不会留下部分题目。

可直接编辑并上传以下模板：

```text
eval/templates/eval-case-template.xlsx
eval/templates/eval-case-template.md
eval/templates/eval-case-template.json
```

每次成功导入都会保留原始文件。文件按用户隔离保存在：

```text
data/eval-imports/user-<用户ID>/<随机UUID>.<扩展名>
```

导入元数据保存在 PostgreSQL 的 `eval_imports`，包括原始文件名、类型、大小、导入题目数和时间。可在前端“导入记录”查看和下载自己的文件，或调用：

```text
GET /api/eval/imports
GET /api/eval/imports/{id}/download
```

存储根目录可通过环境变量配置：

```bash
export EVAL_IMPORT_DIRECTORY='/path/to/eval-imports'
```

健康检查可以访问：

```bash
curl http://localhost:8080
```

如果没有根路径接口，看到 404 也说明服务已启动，继续调用业务接口即可。

## 如何导入文档

把 Markdown 或文本文件放到 `docs` 目录：

```text
docs/
  spring-ai-notes.md
  mcp-notes.md
  rag-notes.md
```

调用导入接口：

```bash
curl -X POST http://localhost:8080/api/ingest
```

也可以使用文档管理接口。单文件导入：

```bash
curl -X POST http://localhost:8080/api/documents/ingest \
  -H 'Content-Type: application/json' \
  -d '{"path":"docs/mcp-notes.md"}'
```

目录批量导入，默认读取 `docs`：

```bash
curl -X POST http://localhost:8080/api/documents/ingest-directory
```

指定目录批量导入：

```bash
curl -X POST http://localhost:8080/api/documents/ingest-directory \
  -H 'Content-Type: application/json' \
  -d '{"path":"docs"}'
```

强制重新导入单文件：

```bash
curl -X POST http://localhost:8080/api/documents/ingest \
  -H 'Content-Type: application/json' \
  -d '{"path":"docs/mcp-notes.md","force":true}'
```

完全重建索引：

```bash
curl -X POST http://localhost:8080/api/documents/rebuild
```

全局文档导入、目录扫描、同步、重建以及公共文档删除仅允许数据库系统角色为 `ADMIN` 或 `SUPER_ADMIN` 的用户执行。账号 `admin` 固定为最高权限 `SUPER_ADMIN`，应用启动时会自动修正存量 `admin` 的数据库角色；该账号不能通过公开注册接口创建，也不能在用户管理接口中被降级。

旧版本通过环境变量配置的管理员账号仍可平滑迁移，多个账号使用逗号分隔：

```text
ADMIN_ACCOUNTS=admin,owner@example.com
```

应用启动时会把 `ADMIN_ACCOUNTS` 中已存在的账号一次性写为数据库 `ADMIN` 角色；之后可以在用户管理页面调整，运行期授权不再依赖环境变量。未配置时，除数据库中已有管理员和固定的 `admin` 超级管理员外，其他普通用户不能执行全局管理操作。普通用户只能枚举公共文档和自己有权访问的空间文档。

### 用户管理

管理员登录后，顶部导航会显示“用户管理”页面：

```text
GET /api/admin/users
PUT /api/admin/users/{publicId}/role
```

- `ADMIN` 和 `SUPER_ADMIN` 可以查看全部用户、账号、公开 ID、注册时间和系统角色。
- 只有 `SUPER_ADMIN` 可以将其他用户在 `USER` 与 `ADMIN` 之间切换。
- 不允许通过接口授予第二个 `SUPER_ADMIN`。
- `admin` 账号永久保持 `SUPER_ADMIN`，不能降级。
- 用户角色变更会持久化为 `USER_ROLE_CHANGE` 业务审计事件。
- 当前不提供物理删除用户，避免级联破坏会话、文档、空间成员关系和审计记录。

## 知识空间与成员角色

系统已建立知识空间与成员关系模型：

```text
WorkspaceType = PERSONAL | TEAM | PUBLIC
WorkspaceRole = OWNER | EDITOR | VIEWER
```

用户首次访问空间列表时会自动创建 `personal-<userId>` 个人空间，存量用户不需要单独执行迁移。团队空间可由普通用户创建；公共空间仅允许通过 `ADMIN_ACCOUNTS` 配置的系统管理员创建。

空间 API：

```text
GET    /api/workspaces
POST   /api/workspaces/team
POST   /api/workspaces/public
GET    /api/workspaces/{workspaceId}/members
POST   /api/workspaces/{workspaceId}/members
PUT    /api/workspaces/{workspaceId}/members/{memberPublicId}
DELETE /api/workspaces/{workspaceId}/members/{memberPublicId}
GET    /api/workspaces/{workspaceId}/audit-events
```

创建团队空间：

```json
{
  "name": "研发团队"
}
```

添加成员：

```json
{
  "account": "member@example.com",
  "role": "EDITOR"
}
```

当前成员权限规则：

- `OWNER` 可以添加、移除成员以及修改成员角色。
- `EDITOR` 和 `VIEWER` 可以查看空间成员，但不能管理成员。
- 个人空间不能添加其他成员。
- 不能添加第二个 `OWNER`，也不能降级或移除现有 `OWNER`。
- 非成员访问空间统一返回空间不存在，避免泄露空间是否存在。
- 暂不支持所有权转移。
- 仅 `OWNER` 可以查看当前空间最近 200 条业务审计事件。

文档索引和 Chroma chunk metadata 已增加 `workspaceId` 与 `visibility`。旧数据按安全规则转换：有 owner 的文档归入 `personal-<ownerUserId>` 并设为 `PRIVATE`，无 owner 的文档归入 `public-default` 并设为 `PUBLIC`。

Chroma 相似度查询会在向量库召回阶段强制附加 `ownerUserId`、`workspaceId` 和 `visibility` metadata 过滤，只召回公共文档、当前用户在当前空间的私有文档以及当前空间文档。应用层仍会再次执行相同的可见性校验；Chroma 不可用时，内存回退也会在排序和截断前执行范围过滤。

当前文档可见性规则：

- `PUBLIC`：登录用户可读取和检索。
- `PRIVATE`：只有 `ownerUserId` 对应用户可读取和检索。
- `WORKSPACE`：仅对应空间成员可读取和检索。

聊天、流式聊天、检索调试和文档管理请求已接入 `workspaceId`。未传该字段时默认使用用户的 `personal-<userId>` 空间；传入其他空间时，服务会先通过 `workspace_members` 验证成员关系，非成员统一返回 404。

空间上下文传递方式：

```text
POST /api/workbench/chat                 JSON workspaceId
POST /api/workbench/chat/stream          JSON workspaceId
POST /api/rag/chat                       JSON workspaceId
POST /api/rag/debug                      JSON workspaceId
GET  /api/documents                      Query workspaceId
GET  /api/documents/{id}/content         Query workspaceId
DELETE /api/documents/{id}               Query workspaceId
```

空间文档权限：

- `PUBLIC` 文档在任意已授权空间上下文中可读取和检索。
- `PRIVATE` 文档仅在其个人空间中对 owner 可见。
- `WORKSPACE` 文档仅对对应空间成员可读取和检索。
- `VIEWER` 不能删除 `WORKSPACE` 文档。
- `EDITOR` 和 `OWNER` 可以删除当前空间的 `WORKSPACE` 文档。
- 伪造其他团队的 `workspaceId` 会在检索或文档读取前被拒绝。

前端登录后会初始化个人空间，并在顶部提供个人、团队和公共空间切换器。聊天、SSE、检索调试和文档请求会自动携带当前空间 ID；切换空间时会停止当前流式回答、清空旧空间前端上下文并恢复目标空间的会话历史，避免跨空间混用检索上下文。现有服务器路径导入仍属于管理员全局管理能力。

空间切换器旁提供空间管理入口：所有登录用户可以创建团队空间，空间成员可以查看成员列表；团队和公共空间的 `OWNER` 可以添加成员、在 `EDITOR/VIEWER` 间调整角色、移除非所有者成员，并查看当前空间最近 200 条业务审计事件。公共空间创建仍由管理员 API 控制，前端不会依据客户端状态猜测系统管理员身份。

会话列表、消息读取、停止和删除同样按 `userId + workspaceId` 双重约束。新会话会持久化所属空间；升级前没有 `workspaceId` 的历史会话仅归入该用户个人空间。空间切换后前端只恢复新空间的会话历史。

### 空间文档上传

空间成员可以通过 multipart 接口上传文档：

```text
POST /api/documents/upload?workspaceId=<workspaceId>
Content-Type: multipart/form-data
file=<Markdown 或 TXT 文件>
```

上传限制与归属规则：

- 仅支持 UTF-8 编码的 `.md` 和 `.txt`。
- 单个文件最大 5 MB。
- `VIEWER` 不能上传，`EDITOR` 和 `OWNER` 可以上传。
- 上传到个人空间时自动设为 `PRIVATE`。
- 上传到团队空间时自动设为 `WORKSPACE`。
- 上传到公共空间时自动设为 `PUBLIC`。
- 服务端使用随机存储文件名，客户端原文件名仅用于展示。
- 磁盘目录按 `docs/workspaces/<workspaceId>/` 隔离。
- 文档 ID 和 chunk ID 由 `workspaceId + contentHash` 生成；不同空间上传相同内容不会发生 ID 覆盖。

前端知识库页面已经提供“上传到当前空间”入口。删除通过空间上传接口保存的文档时，会先安全删除 `docs/workspaces/<workspaceId>/` 下对应的 UUID 源文件，再删除索引与向量，防止后续同步或重建让已删除文档重新出现。删除路径必须精确匹配当前空间的系统生成文件名，并校验规范化路径与真实路径，拒绝目录穿越和符号链接逃逸。管理员手工维护或全局导入的非 UUID 源文件仍只删除索引与向量，不自动操作磁盘文件。

### 业务审计

系统将以下高风险操作的成功、权限拒绝和执行失败结果持久化到 `audit_events`：

```text
WORKSPACE_CREATE
WORKSPACE_MEMBER_ADD
WORKSPACE_MEMBER_ROLE_CHANGE
WORKSPACE_MEMBER_REMOVE
DOCUMENT_UPLOAD
DOCUMENT_DELETE
```

审计事件包含操作用户 `publicId`、空间 ID、动作、资源类型与标识、结果、固定失败代码、服务端生成的 `requestId` 和发生时间。审计表不保存账号、成员查询账号、文档正文、上传内容、文件路径、请求体、异常消息、密码、Cookie 或 Token。审计采用独立事务；审计存储故障不会回滚已经完成的业务操作，也不会覆盖原始权限错误响应。

查看已导入文档：

```bash
curl http://localhost:8080/api/documents
```

删除文档索引：

```bash
curl -X DELETE http://localhost:8080/api/documents/<documentId>
```

出于安全限制，导入路径必须在项目 `docs` 目录下，支持 `.md` 和 `.txt`。

响应示例：

```json
{
  "files": 3,
  "chunks": 28,
  "imported": 3,
  "skipped": 0,
  "failed": 0,
  "documents": [
    {
      "fileName": "mcp-notes.md",
      "path": "docs/mcp-notes.md",
      "documentId": "abc123...",
      "status": "imported",
      "chunks": 8,
      "reason": "Document imported"
    }
  ]
}
```

含义：

- `files`: 读取到的 `.md` / `.txt` 文件数量。
- `chunks`: 本次成功导入的 chunk 数量。
- `imported`: 本次导入的文件数量。
- `skipped`: 因重复或空文档跳过的文件数量。
- `failed`: 不支持或失败的文件数量。
- `documents`: 每个文件的导入明细。

每个 chunk 会保留 metadata：

```json
{
  "source": "mcp-notes.md",
  "path": "docs/mcp-notes.md",
  "chunkIndex": 0,
  "title": "MCP Notes"
}
```

导入后可以在 Chroma 默认位置找到 collection：

```text
default_tenant / default_database / knowledge_assistant
```

查看 Chroma collections：

```bash
curl http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections
```

## 如何提问

RAG 问答接口：

```bash
curl -X POST http://localhost:8080/api/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"default","message":"MCP 是什么？"}'
```

响应示例：

```json
{
  "answer": "MCP 是 Model Context Protocol 的缩写...",
  "sources": [
    {
      "file": "mcp-notes.md",
      "chunkIndex": 0
    }
  ]
}
```

多轮追问：

```bash
curl -X POST http://localhost:8080/api/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"default","message":"那它和 Tool Calling 有什么区别？"}'
```

`conversationId` 相同的请求会共享会话上下文，系统可以理解“它”指代上一轮提到的 MCP。

空消息会返回固定错误：

```bash
curl -X POST http://localhost:8080/api/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"default","message":""}'
```

响应：

```json
{
  "error": "message cannot be empty"
}
```

## 如何启用搜索

当前项目的搜索入口是 `WebSearchService`。策略是：

```text
先 RAG
如果 RAG 没有结果或上下文不足
再 Search
搜索结果进入 Prompt
回答中标明来自 Web
```

示例：

```bash
curl -X POST http://localhost:8080/api/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"default","message":"Spring AI 2.0 MCP 有哪些新特性？"}'
```

响应会包含类似：

```json
{
  "answer": "知识库没有足够信息，我将使用搜索工具...\n\n...\n\n来自 Web",
  "sources": [
    {
      "file": "Web: https://docs.spring.io/spring-ai/reference/api/mcp/",
      "chunkIndex": -1
    }
  ]
}
```

目前 `WebSearchService` 是本地搜索工具占位实现。要接真实搜索服务，可以把它替换为：

- Tavily
- Bing Search
- SerpAPI
- 自建搜索 API
- MCP search tool

替换位置：

```text
src/main/java/com/example/workbench/tools/WebSearchService.java
```

保持返回模型不变即可：

```java
public record WebSearchResult(
        String title,
        String url,
        String snippet
) {
}
```

## 工具调用日志

项目会记录工具调用原因和 confidence。

## Log4j2 日志

项目使用 Log4j2 输出后端日志，配置文件在：

```text
src/main/resources/log4j2-spring.xml
```

控制台日志格式包含时间、级别、线程、logger 和消息：

```text
2026-07-24 19:20:00.123 INFO  [http-nio-8080-exec-1] c.e.w.config.HttpRequestLoggingFilter - HTTP request completed requestId=... method=POST path=/api/workbench/chat status=200 durationMs=1234
```

API 请求日志会按日期和接口路径拆分文件：

```text
logs/2026-07-24/api_workbench_chat.log
logs/2026-07-24/api_documents_ingest.log
logs/2026-07-24/api_documents.log
```

同时保留全局应用日志和全局错误日志：

```text
logs/my-knowledge-assistant.log
logs/my-knowledge-assistant-error.log
```

全局日志记录应用启动、Spring/Tomcat 日志、业务日志和 API 请求日志；接口路径日志只记录 `HttpRequestLoggingFilter` 产生的 HTTP 请求/响应摘要。

每条 API 日志包含：

- `requestId`
- HTTP method
- path
- status
- durationMs
- query 参数
- JSON body 请求参数 `requestBody`
- JSON body 返回参数 `responseBody`

模型调用日志会记录：

- `primaryModel` 和 `fallbackModels`
- `requestTimeoutMs`
- `retryMaxAttempts` 和 `retryBackoffMs`
- `fallbackStrategy`
- 实际调用的 `model` 和 `responseModel`
- 模型返回 `responseId`
- `finishReason`
- token usage：`promptTokens`、`completionTokens`、`totalTokens`
- 结构化错误：`errorType`、`httpStatus`、`exceptionClass`、`rootCauseClass`
- 是否可重试：`retryable`、`willRetry`、`willSwitchModel`

示例：

```text
AI model call completed provider=openai-compatible model=gpt-5.5 responseModel=gpt-5.5 responseId=chatcmpl-... conversationId=default attempt=1 finishReason=stop promptTokens=1024 completionTokens=256 totalTokens=1280 answerLength=512 durationMs=1180
AI model call failed provider=openai-compatible model=gpt-5.5 conversationId=default attempt=1 maxAttempts=2 durationMs=30000 errorType=timeout httpStatus=null exceptionClass=AiModelCallException rootCauseClass=TimeoutException retryable=true willRetry=true willSwitchModel=false fallbackStrategy=local-answer errorMessage=AI model request timed out after 30000ms responseBody=
```

SSE 流式接口不缓存返回流，`responseBody` 会记录为 `<streaming response not captured>`，避免影响流式输出。

示例：

```text
2026-07-24 19:30:00.123 INFO [http-nio-8080-exec-1] requestId=abc c.e.w.config.HttpRequestLoggingFilter - HTTP request completed requestId=abc method=POST path=/api/documents/ingest status=200 durationMs=45 query= requestBody={"path":"docs/mcp-notes.md"} responseBody={"imported":1,"skipped":0,"failed":0}
```

日志会自动脱敏常见敏感字段，例如 `apiKey`、`authorization`、`token`、`password`、`secret`。

日志会按日期和大小滚动，归档到：

```text
logs/2026-07-24/archive/
```

示例日志：

```text
Tool call: tool=RAG, reason=用户要求总结知识库内容，需要先检索相关文档, confidence=0.92
Tool call: tool=judge, reason=检查总结是否基于检索上下文, confidence=0.86
```

实现位置：

```text
src/main/java/com/example/workbench/advisor/ToolCallLogger.java
```

## 目录说明

```text
docs/      知识库源文档
src/       Spring Boot 应用源码
frontend/  Vue 前端源码
```

## 常见问题

如果启动时报 Chroma collection 不存在，确认 Chroma 正在运行，并重启应用。项目会自动创建：

```text
default_tenant / default_database / knowledge_assistant
```

如果 IntelliJ 仍使用旧 class，执行：

```text
Build -> Rebuild Project
```

如果模型调用失败，应用会 fallback 到本地占位回答，并打印 warn 日志。
