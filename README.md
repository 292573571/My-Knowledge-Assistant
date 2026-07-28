# My Knowledge Assistant

一个基于 Spring Boot 和 Spring AI 的个人知识库助手实验项目。项目支持文档导入、RAG 问答、多轮对话、Chroma 向量数据库、OpenAI-compatible 模型、受限 filesystem 文件操作、搜索兜底、答案质量检查和工具调用日志。

## 项目功能

- 读取 `docs` 目录下的 `.md` / `.txt` 文档。
- 将文档切分成 chunks，并保留 `source`、`path`、`chunkIndex`、`title` metadata。
- 使用 Spring AI Embedding 将 chunks 向量化。
- 将向量写入 Chroma collection：`knowledge_assistant`。
- 支持 `/api/rag/chat` 基于知识库问答。
- 支持 `conversationId` 多轮对话。
- RAG 上下文不足时再调用搜索工具，不会每次都搜索。
- 支持文件生成任务，例如总结 MCP 相关内容并写入 `notes/mcp-overview.md`。
- filesystem 操作限制在项目内 `docs` 和 `notes`，不授权整个用户目录。
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
  |
  +--> 文件操作 ----------------> NoteAssistantService
  |                                |
  |                                +--> WorkspaceFileService
  |                                       |
  |                                       +--> read: docs / notes
  |                                       +--> write: notes only
  |
  +--> 总结任务 ----------------> SummaryWorkflowService
                                   |
                                   +--> RAG 检索相关文档
                                   +--> 生成总结
                                   +--> AnswerJudge 检查
                                   +--> filesystem 写入 notes

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

脚本会读取 `src/main/resources/application.properties`，并以 `app.mode=eval` 启动 Spring Boot，执行 `eval/questions.jsonl` 中的用例，输出到：

```text
eval/results/latest.json
eval/reports/latest.md
```

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

## 如何启用 MCP filesystem

当前项目实现的是受限 filesystem 工具，行为等价于只授权工作区内的 `docs` 和 `notes`，不会授权整个用户目录。

读取边界：

```text
允许读取：docs / notes
禁止读取：用户目录、绝对路径、.. 路径穿越
```

写入边界：

```text
允许写入：notes
禁止写入：docs、用户目录、绝对路径、.. 路径穿越
```

文件总结示例：

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"请总结 mcp-notes.md 并写入 mcp-summary.md"}'
```

执行后创建：

```text
notes/mcp-summary.md
```

创建学习计划示例：

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"请帮我在 notes 目录下创建一个 rag-learning-plan.md"}'
```

执行后创建：

```text
notes/rag-learning-plan.md
```

RAG + filesystem 联合任务：

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"请总结 docs 里的 MCP 相关内容，并写成 mcp-overview.md"}'
```

系统会自动执行：

- 识别为文件生成任务。
- RAG 检索 MCP 相关文档。
- 生成总结。
- judge 检查总结是否基于上下文。
- 调用受限 filesystem 写入 `notes/mcp-overview.md`。
- 返回写入成功和文件路径。

响应示例：

```json
{
  "answer": "已识别为文件生成任务，完成 RAG 检索、总结生成和 filesystem 写入。文件路径：notes/mcp-overview.md"
}
```

如果你要接真正的 MCP filesystem server，建议只授权项目目录或 `notes` 目录，不要授权整个用户目录。

示例边界：

```text
允许：/Users/95h/95h/95h-ai-workspace/my-knowledge-assistant
或：/Users/95h/95h/95h-ai-workspace/my-knowledge-assistant/notes
禁止：/Users/95h
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
Tool call: tool=MCP filesystem, reason=用户要求把总结写成文件，只允许写入 notes 目录, confidence=0.97
```

实现位置：

```text
src/main/java/com/example/workbench/advisor/ToolCallLogger.java
```

## 目录说明

```text
docs/      知识库源文档
notes/     助手生成的总结、学习计划等文件
src/       Spring Boot 应用源码
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
