# Spring AI 1.0.0 → 1.1.8 升级影响评估

- 评估日期：2026-09-03
- 评估方式：只读调研（javap 反编译对比、Maven Central 元数据解析、/tmp 隔离探针项目实跑）
- 结论：**可行，项目代码大概率零改动，风险可控**
- 未改动业务项目任何文件

## 一、为什么要升

项目当前 Spring AI 1.0.0 携带的 MCP Java SDK 是 `0.10.0`，**只有 SSE 双端点传输**（`/sse` + `/mcp/message`），没有 Streamable HTTP。MCP 规范自 2025-03-26 起已把 SSE 标记为 deprecated，推荐 Streamable HTTP。升级到 1.1.8 可拿到 SDK `0.18.3` 与 Streamable HTTP 支持。

## 二、版本事实

| 项 | 值 | 证据 |
|---|---|---|
| 1.1.x 最新 GA | **1.1.8** | Maven Central `spring-ai-bom/maven-metadata.xml` |
| Boot 兼容范围 | 官方 Getting Started 原文 "Spring AI supports Spring Boot 3.4.x and 3.5.x" | docs.spring.io/spring-ai/reference/1.1/getting-started.html |
| 项目 Boot 版本 | 3.4.5 | pom.xml |
| starter 编译基线 | `spring-boot-starter:3.5.15`（1.0.0 是 3.4.5） | `spring-ai-starter-mcp-server-webmvc-1.1.8.pom` |

> 最后一行是本次最大的隐藏风险：**编译依赖 3.5.15、运行用 3.4.5**。官方文档不足以证明二进制兼容，因此用探针实测（见第四节）。

## 三、API 兼容性：javap 逐项 diff（1.0.0 vs 1.1.8）

全部**无差异**的项：

| API | 用途 | 结果 |
|---|---|---|
| `ChatClient$ChatClientRequestSpec` | 全量方法 diff（含 `tools(Object...)`、`messages(List<Message>)`、`system`、`user`） | 空 diff |
| `OpenAiApi$Builder` | `baseUrl` / `apiKey` / `completionsPath` / `embeddingsPath` / `restClientBuilder` | 一致 |
| `OpenAiChatModel$Builder` | `openAiApi` / `defaultOptions` | 一致 |
| `OpenAiChatOptions$Builder` | `model` / `temperature` / `maxTokens` / `topP` | 一致 |
| `ChatResponse` | `getResult` / `getResults` / `getMetadata` | 一致 |
| `UserMessage(String)` / `AssistantMessage(String)` | 构造器 | 存在 |
| `@Tool` / `@ToolParam` | 注解，`ToolParam.required()` 仍存在 | 一致 |
| `AbstractVectorStoreBuilder` | `batchingStrategy` | 一致 |

### Chroma（项目最高风险点，实测安全）

`SpringAiConfig` 同时引用两个不同包，这种混合状态在升版时最容易炸，因此逐一验证：

```
org.springframework.ai.chroma.vectorstore.ChromaVectorStore          → 1.1.8 仍在
org.springframework.ai.chroma.vectorstore.ChromaApi                  → 1.1.8 仍在
org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreProperties → 1.1.8 仍在
```

Builder 的 `tenantName` / `databaseName` / `collectionName` / `initializeSchema` / `filterExpressionConverter` / `initializeImmediately` / `build()` 全部保留，与 1.0.0 逐字节一致。

### 配置属性（全部保留）

```
spring.ai.openai.base-url / api-key / chat.options.model
spring.ai.openai.embedding.base-url / api-key / options.model
spring.ai.vectorstore.chroma.client.host / client.port
spring.ai.vectorstore.chroma.tenant-name / database-name / collection-name / initialize-schema
spring.ai.retry.max-attempts
```

逐条在 1.1.8 的 `META-INF/spring-configuration-metadata.json` 中命中。

### 已知破坏性变更（项目均未触及）

1. `ChatClient.create/builder(ChatModel, ObservationRegistry, ChatClientObservationConvention)` 3 参重载 → 4 参（新增 `AdvisorObservationConvention`）。**项目用注入的 `ChatClient.Builder`，安全。**
2. `UserMessage.properties` → `metadata`。**项目只用 `new UserMessage(String)`，安全。**
3. TTS 旧包 `org.springframework.ai.openai.audio.speech.*` 已删除，迁到 `org.springframework.ai.audio.tts.*`。**项目未用。**
4. `OpenAiChatOptions` 的 `maxTokens` 与 `maxCompletionTokens` 互斥。**项目只用 maxTokens，安全。**
5. MCP autoconfigure 包拆分（`mcp.server.common.autoconfigure` / `server-webmvc`）。**新代码按 1.1.8 写即可。**

## 四、探针实证：Boot 3.4.5 + Spring AI 1.1.8

在 `/tmp/sa11-probe` 建隔离项目（parent = `spring-boot-starter-parent:3.4.5`，dependencyManagement 导入 `spring-ai-bom:1.1.8`），覆盖项目实际用到的每一个 API 形状。

**结果：8/8 通过**

- Spring 上下文加载成功（含 MCP Server 自动配置，`spring.ai.mcp.server.protocol=STREAMABLE` 绑定成功）
- `ChatClient.Builder` bean 可用，fluent API（`.prompt().messages(...)`）可用
- `ChromaVectorStore.builder(...)` 全链调用（mocked ChromaApi + EmbeddingModel）成功
- `OpenAiApi.builder(...)` / `OpenAiChatOptions.builder(...)` 成功
- `@Tool` / `@ToolParam` 可加载，`required()` 方法存在
- `McpServerProperties` 可绑定

**一次失败记录（已排除，非兼容性问题）**：首次运行时 `ChromaVectorStoreAutoConfiguration` 在 `afterPropertiesSet()` 里向 `http://127.0.0.1:8000` 发起 HTTP GET 查询 collection，本地无服务导致 `ConnectException`。用 `spring.autoconfigure.exclude` 排除后全绿。

> 副作用发现：自动配置请求的 URL 是 `.../tenants/SpringAiTenant/databases/SpringAiDatabase/collections/SpringAiCollection`，即**默认租户/库名与项目配置不符**。这从反面印证了项目 `SpringAiConfig` 手工构建 `ChromaVectorStore` 的必要性（其注释已记录：1.0.0 自动配置遗漏了 tenant/database builder 参数），1.1.8 依旧如此，**不要删掉这个类**。

## 五、MCP 能力（1.1.8）

- MCP Java SDK：`0.10.0` → **`0.18.3`**
- 自动配置类实测存在三个：`McpServerSseWebMvcAutoConfiguration`、`McpServerStreamableHttpWebMvcAutoConfiguration`、`McpServerStatelessWebMvcAutoConfiguration`
- 新增属性：`spring.ai.mcp.server.protocol`（SSE / STREAMABLE / STATELESS）、`spring.ai.mcp.server.streamable-http.mcp-endpoint`（默认 `/mcp`）、`keep-alive-interval`、`disallow-delete`、`spring.ai.mcp.server.annotation-scanner.enabled`
- **SSE 端点保留**：`spring.ai.mcp.server.sse-endpoint` / `sse-message-endpoint` 仍在，可灰度切换
- `spring-ai-starter-mcp-server-webmvc` artifact 名未变，BOM 仍管理，加依赖不用写 version

## 六、升级步骤（待执行）

1. `pom.xml`：`spring-ai-bom` 版本 `1.0.0` → `1.1.8`
2. 首次 `mvn` 需联网拉取 1.1.8 依赖（本地 m2 尚无），之后可 `-o` 离线
3. `mvn -o test` 全量回归（当前基线：**346 个用例全过**）
4. **线上真实启动验证**（不可替代，见下）
5. 部署后核对启动日志：`MCP Server` 初始化、`/mcp` 端点握手、`/api/health` 200

## 七、风险与注意

1. **单测覆盖不到真实启动**。项目 **0 个 `@SpringBootTest`**，全是纯单元测试。这意味着升级只要能编译通过，346 个测试就全绿，但**线上能否启动成功完全没有被测试保护**。探针虽已实证上下文可加载，仍需在服务器真实启动一次并核验日志。
2. **探针未覆盖真实的 OpenAI / Chroma 网络调用路径**（需要真实凭据与服务）。上线后必须跑一次真实 RAG 问答验证。
3. 升级与 MCP 落地的顺序建议：先单独升级 1.1.8 并验证线上稳定，再开始加 MCP 代码。两者混在一起出问题时难以定位。
