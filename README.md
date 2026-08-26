# My Knowledge Assistant

一个面向个人学习和团队知识管理的 RAG 知识助手。项目使用 Spring Boot、Spring AI、PostgreSQL、Chroma 和 Vue，支持多用户知识空间、文档异步导入、混合检索、多轮对话、流式回答、工作空间层级隔离和评测。

当前版本已经适合作为 RAG 应用和 Agent 学习底座，但联网搜索仍是扩展点，不应当按完整搜索产品理解。

## 功能概览

- 用户注册、登录、Cookie 会话、资料和头像管理。
- 工作空间两级层级：组织（ORG）根空间 → 下挂团队（TEAM）子空间；组织文档对所有下属团队可见，团队文档仅本团队可见。启动时自动幂等创建 `sunline` 根组织并把现有无上级团队挂到其下。
- `OWNER`、`EDITOR`、`VIEWER` 三级空间成员权限。
- Markdown、TXT、HTML、DOCX、PDF、PNG、JPG/JPEG 导入。
- PDF 文本层、扫描页 OCR 和混合 PDF 逐页处理。
- 长 PDF 分批解析、批次状态、失败清理和成功批次跳过恢复。
- Chroma 向量检索、PostgreSQL 稀疏检索、混合检索和 RRF 排序。RAG 检索按「可读空间集合」过滤——组织成员可检索整个子树文档，团队成员可检索本团队加祖先链文档。
- 多轮聊天、SSE 流式回答、会话保存、停止和删除。
- 统一识海教学助手：自动区分直接回答与主题教学，支持 EXPLAIN、CHECK、PRACTICE、REVIEW 状态恢复和幂等提交。
- PostgreSQL 持久化学习记录、正式笔记和教学学习资产，Markdown 与 RAG 索引作为可重建投影。
- 来源页码展示、答案依据校验、模型兜底和工具调用记录。
- 模型配置 RBAC：用户自管 CHAT 模型（跟随默认 / 池模型 / 自定义），超管管理 EMBEDDING 模型和全局池默认模型。
- 模型服务商错误中文化：`ModelProviderException` 把厂商原始错误码（限流、配额、鉴权等）统一转为面向用户的中文提示。
- 评测题库、规则评测、检索指标、运行记录和质量门禁。
- 前端首页引导（HomePage + OnboardingTour）、检索诊断面板（RetrievalDebug）。
- Actuator、请求 ID、结构化日志和敏感信息脱敏。

数据库日志中心通过 Log4j2 有界异步队列写入 PostgreSQL，不阻塞业务线程。队列大小可通过 `LOG_JPA_QUEUE_SIZE` 配置，默认 `8192`；队列满时优先保证业务请求和控制台/文件日志，数据库日志可能丢弃。

日志结构化上下文可通过 `INSTANCE_ID` 和 `APP_ENVIRONMENT` 标识实例与环境，默认分别为 `unknown` 和 `development`；请求会自动记录 `requestId`、`traceId`、用户 ID、知识空间 ID，以及异常类型和堆栈。

普通运行日志与审计日志分开保存。普通运行日志写入 `system_log`，文件日志按天和大小轮转，数据库记录按保留策略清理，系统管理员可以在日志中心清理普通运行日志；清理不会影响审计日志。

审计日志写入独立的 `audit_events`，记录登录成功/失败、退出登录、密码修改、空间成员和角色变更、文档上传/删除、系统角色变更及模型配置修改。V17 迁移为审计表增加 PostgreSQL 只追加触发器和 SHA-256 哈希链；普通管理员只有查询权限，删除接口仅允许超级管理员调用。删除动作会写入不可删除的 `audit_purge_events` 留痕表，数据库运维也应限制直接修改审计表的权限。

当前默认关闭联网搜索：`workbench.rag.web-search.enabled=false`。`WebSearchService` 是搜索扩展点，需要接入真实搜索服务后再开启。

## 技术栈

- 后端：Java 17、Spring Boot 3.4.5、Spring AI 1.0.0、Spring Data JPA。
- 数据库：PostgreSQL、Flyway、Hibernate Schema Validate。
- 向量库：Chroma，Chroma 不可用时保留内存回退能力。
- 文档处理：PDFBox、Apache POI、Jsoup、Tesseract OCR。
- 前端：Vue、Vite、Yarn、Markdown-it、highlight.js、DOMPurify。

## 运行前准备

需要安装：

- Java 17。
- Maven 3.9+。
- Node.js 20+ 和 Yarn。
- PostgreSQL。
- Chroma。仓库不包含 Docker Compose 或 Dockerfile，需要自行部署。
- Tesseract 及 `chi_sim`、`eng` 语言包。只有图片或扫描 PDF OCR 时需要。

macOS：

```bash
brew install maven tesseract tesseract-lang
```

Debian/Ubuntu：

```bash
sudo apt-get install maven tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng
```

创建 PostgreSQL 数据库，例如：

```sql
CREATE DATABASE knowledge_assistant;
```

## 环境变量

最小后端配置：

```bash
export POSTGRES_URL='jdbc:postgresql://localhost:5432/knowledge_assistant'
export POSTGRES_USER='postgres'
export POSTGRES_PASSWORD='postgres'
export OPENAI_API_KEY='聊天模型 API Key'
export OPENAI_EMBEDDING_API_KEY='Embedding API Key'
```

默认模型和服务配置在 `src/main/resources/application.properties`。管理员可通过 `/api/model-config/pool` 管理全局模型池并设置默认模型；用户可在前端「模型配置」页面或 `/api/model-config/me` 选择自己的模型模式（跟随默认 / 使用池模型 / 自定义），运行时按用户配置动态解析 ChatClient。

当前默认 Chroma 地址是配置文件中的远程地址。连接本地 Chroma 时覆盖：

```bash
export SPRING_AI_VECTORSTORE_CHROMA_CLIENT_HOST='http://localhost'
export SPRING_AI_VECTORSTORE_CHROMA_CLIENT_PORT='8000'
```

常用可选变量：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `ADMIN_ACCOUNTS` | 空 | 启动时把已有账号迁移为 `ADMIN` |
| `AUTH_COOKIE_SECURE` | `true` | 本地 HTTP 开发环境临时设为 `false`，生产环境必须保持 `true` |
| `MAIL_IP_WINDOW_MINUTES` | `60` | 邮箱验证码 IP 限流窗口 |
| `MAIL_CODE_MAX_ATTEMPTS` | `5` | 单个验证码最大失败次数 |
| `MAIL_MAX_IP_SENDS` | `10` | 单个 IP 在限流窗口内最多发送次数 |
| `FILE_ALLOWED_DIRECTORIES` | `data,docs` | 文件工具允许读取的目录，生产环境应使用明确绝对路径 |
| `FILE_MAX_READ_BYTES` | `10485760` | 文件工具单次读取上限 |
| `OCR_MAX_CONCURRENT` | `2` | OCR 同时运行任务数 |
| `OCR_MAX_OUTPUT_BYTES` | `2097152` | 单次 OCR 输出上限 |
| `AVATAR_DIRECTORY` | `data/avatars` | 头像目录 |
| `SMTP_HOST` | 空 | 邮箱 SMTP 服务器，例如 QQ 邮箱 `smtp.qq.com` |
| `SMTP_PORT` | `465` | SMTP 端口 |
| `SMTP_USERNAME` | 空 | SMTP 登录账号（邮箱地址） |
| `SMTP_PASSWORD` | 空 | SMTP 授权码（不是邮箱登录密码） |
| `SMTP_SSL_ENABLE` | `true` | 是否启用 SSL |
| `EVAL_IMPORT_DIRECTORY` | `data/eval-imports` | 评测导入文件目录 |
| `OCR_COMMAND` | `tesseract` | OCR 命令路径 |
| `OCR_LANGUAGES` | `chi_sim+eng` | OCR 语言 |
| `OCR_TIMEOUT_SECONDS` | `120` | 单页 OCR 超时 |
| `PDF_BATCH_PAGES` | `50` | PDF 每批页数 |
| `OCR_MAX_PAGES` | `5000` | PDF 最大总页数 |
| `OCR_MAX_OCR_PAGES` | `50` | 单批 OCR 页数上限 |
| `MANAGEMENT_SERVER_PORT` | `8081` | Actuator 管理端口 |
| `WORKBENCH_EVAL_GATE_ENABLED` | `false` | 是否启用评测质量门禁 |

完整默认项见 `src/main/resources/application.properties`。

邮箱验证码使用 SMTP 发送。未配置 `SMTP_HOST` 时不会发送邮件，也不会把验证码写入日志；本地验证流程应配置开发 SMTP 或使用测试替身。验证码哈希和发送记录保存在 PostgreSQL 中，并按邮箱、IP 和失败次数限流。使用 QQ 邮箱时的配置示例：

```bash
export SMTP_HOST='smtp.qq.com'
export SMTP_PORT='465'
export SMTP_USERNAME='你的QQ邮箱@qq.com'
export SMTP_PASSWORD='QQ邮箱SMTP授权码'
export SMTP_SSL_ENABLE='true'
```

QQ 邮箱授权码获取方式：登录 QQ 邮箱 → 设置 → 账户 → 开启「POP3/SMTP 服务」，按提示生成授权码（不是 QQ 登录密码）。项目根目录的 `.env.example` 也包含一份完整的 SMTP 示例。

## 启动项目

启动后端：

```bash
mvn spring-boot:run
```

或打包后启动：

```bash
mvn -DskipTests package
java -jar target/my-knowledge-assistant-0.0.1-SNAPSHOT.jar
```

后端默认地址：`http://localhost:8080`。

健康检查：

```bash
curl http://localhost:8080/api/health
curl http://127.0.0.1:8081/actuator/health
```

前端：

```bash
cd frontend
yarn install --frozen-lockfile
yarn dev
```

前端默认地址：`http://localhost:5173`。开发代理目标由 `frontend/vite.config.mjs` 配置；修改该文件后，才能把 `/api` 请求切换到本地后端。

生产构建：

```bash
cd frontend
yarn build
yarn preview
```

## 统一教学助手接口

前端主入口现在是统一的学习助手工作台。普通问题默认走 `CHAT`，包含“教我、讲解、解释、学习”等明确学习意图的问题自动升级为 `GUIDED`；也可以在界面中显式选择模式。

核心接口如下：

```text
POST /api/learning-assistant/sessions
GET  /api/learning-assistant/sessions
GET  /api/learning-assistant/sessions/{sessionId}
DELETE /api/learning-assistant/sessions/{sessionId}
POST /api/learning-assistant/sessions/{sessionId}/messages
POST /api/learning-assistant/sessions/{sessionId}/messages/stream
POST /api/learning-assistant/sessions/{sessionId}/check
POST /api/learning-assistant/sessions/{sessionId}/practice
POST /api/learning-assistant/sessions/{sessionId}/stop
```

统一 session 使用 `learning_sessions` 保存 workspace、主题、模式、阶段和状态，聊天正文继续复用 `chat_conversations/chat_messages`，教学过程继续复用 `teaching_attempts` 和 `learning_records`。带 `clientRequestId` 的消息、CHECK、PRACTICE 请求会保存到 `learning_session_events`，重复提交直接返回第一次响应；同一 ID 携带不同内容会返回冲突，处理中请求有租约回收机制。前端会在 SSE EOF、停止、组件卸载和会话切换时收口本地请求，后端 stop 接口负责取消服务端执行。

旧入口仍保持兼容：`/api/workbench/chat*` 和 `/api/agent/teaching/*` 不在本次改造中删除。

本轮审计还将文档上传幂等约束收紧为 `(actor_user_id, workspace_id, client_request_id)`，避免不同用户或知识空间因为复用请求 ID 互相冲突。对应数据库迁移为 V10；已有部署升级前应先备份数据库并检查旧的单列唯一约束。

## 流式回答韧性(断点续传 + 模型熔断)

学习助手流式回答过去一旦上游模型超时，就会"答到一半 + 红框报错 + 半截答案丢失"。现在从三层解决这一问题，既不牺牲流式体验，也能从断点接回：

1. **生成与推送解耦 + 断点续传**。后端每个请求以 `streamId`（默认即 `clientRequestId`）对应一个 `StreamSession`（`streaming` 包）：首访负责启动生成任务并写入带全局序号的有序事件；后续访问（含客户端断线重连）只作为订阅者，携带 `Last-Event-ID` 从断点重放历史片段并实时接收后续片段。客户端断开时只解除当前连接的 SSE 推送，生成任务继续跑完并写入缓冲，因此重连即可无感接回，半截答案不丢。
2. **心跳保活**。`SseEmitter` 每 `app.ai.stream.heartbeat-ms`（默认 15000ms）发送一条 SSE 注释（`:keepalive`），避免 nginx / 网关把空闲连接掐断；响应头显式 `X-Accel-Buffering: no` 与 `Cache-Control: no-cache, no-transform`，禁止任何代理层缓冲。
3. **模型熔断 + 回退链**。`ModelCircuitBreaker`（per-model closed/open/half-open）接入 `LocalChatClient` 的回退决策：主模型连续失败达到阈值后熔断，冷却期内请求直接走备用模型；成功后自动恢复。熔断状态通过 `CircuitBreakerStateStore` 抽象，Redis 可用时多实例共享同一模型的健康判断，避免每个实例各自试错。

前端（`LearningAssistantPage.vue` + `learningAssistantApi.js`）配套行为：

- 已收到的 token 始终保留在界面上，出错时不清空，只在底部显示灰色「回答中断，正在自动恢复…」轻提示，而不是红色 error 框。
- 区分两类中断：连接中断（SSE 意外 EOF / 网络错误，标记为 `synthetic`）复用同一 `streamId` + `Last-Event-ID` 断点续传；服务端真实生成失败则自动重建会话、从 `seq=0` 重放新答案并替换旧半截，避免重复拼接。
- 最多自动重试 1 次；若仍失败，提供「重新生成」按钮（以全新 `clientRequestId` 发起）。

相关配置（均带默认值，可直接覆盖）：

```properties
app.ai.stream.heartbeat-ms=15000
app.ai.stream.buffer-ttl-seconds=300
app.ai.stream.buffer-backend=auto          # auto | redis | memory
app.ai.circuit-breaker.failure-threshold=3
app.ai.circuit-breaker.cooldown-ms=30000
spring.data.redis.host=${REDIS_HOST:}      # 留空则自动降级为进程内缓冲
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD:}
```

**缓冲后端自动降级**：`StreamBufferBackend` 接口有两个实现——`MemoryStreamBufferBackend`（进程内，零依赖）和 `RedisStreamBufferBackend`（ZSet 有序存 chunk + Hash 存状态 + Pub/Sub 跨实例通知，全部 key 带 TTL）。`buffer-backend=auto` 时启动探活 Redis，可达则用 Redis，不可达则回落进程内并打 WARN，不阻塞启动。本地开发与单元测试无需安装 Redis。Redis 安装步骤见 `docs/runbooks/redis-setup.md`。

## 发版与启动脚本

项目只保留一个发版入口：

```bash
./deploy/deploy-shihai
```

它会在本地完成后端测试打包、前端构建、上传发布包，然后在服务器上停止并启动 `shihai` 服务，执行服务器本地和公网健康检查。服务器部署端使用同一个脚本的内部子命令，不再维护第二份启动逻辑。

首次配置或更新服务器部署脚本时，在本地项目根目录执行一次：

```bash
scp -i "$DEPLOY_KEY" -P "$DEPLOY_PORT" deploy/deploy-shihai "$DEPLOY_USER@$DEPLOY_HOST:/tmp/deploy-shihai"
ssh -tt -i "$DEPLOY_KEY" -p "$DEPLOY_PORT" "$DEPLOY_USER@$DEPLOY_HOST" \
  "sudo install -m 750 /tmp/deploy-shihai /usr/local/sbin/deploy-shihai && rm -f /tmp/deploy-shihai"
```

并为 `deploy` 用户配置只允许执行这个固定脚本的免密 sudo，例如：

```text
deploy ALL=(root) NOPASSWD: /usr/local/sbin/deploy-shihai *
```

服务器预检会分别检查 `rsync` 和 sudo 权限；如果失败，会明确提示是缺少 `rsync`，还是 sudoers 不允许执行部署脚本。服务器上可以分别验证：

```bash
command -v rsync
su - deploy -c 'sudo -n /usr/local/sbin/deploy-shihai --help'
```

发版脚本不会要求 `sudo -n true`，也不会要求 `deploy` 用户免密执行任意 root 命令。后续发版只上传发布包并调用这个固定部署脚本；修改部署脚本本身时，需要重新手工安装一次。脚本会在发版前检查服务器脚本是否支持 `remote` 和 `cleanup` 子命令。

只有服务器本地健康检查和公网健康检查都成功后，脚本才会删除旧的 release 和备份；任一检查失败时，旧版本仍会保留用于回滚。

## 从 zhihai 迁移到 shihai

项目品牌名已从「智海」改为「识海」，部署服务名、目录、jar 和 SSH 私钥也随之从 `zhihai` 改为 `shihai`。**已在服务器上按旧名部署的环境，必须先执行迁移，否则新发版脚本会创建全新的 `/opt/shihai`，与仍占用 8080 端口的旧 `zhihai` 服务冲突。**

仓库提供了一键迁移脚本，在服务器上以 root 执行：

```bash
sudo bash deploy/migrate-zhihai-to-shihai.sh
```

它会依次完成：

1. 停止并禁用 `zhihai` 服务。
2. 根据 `systemctl show -p FragmentPath zhihai` 找到 unit 文件，生成内容一致的 `shihai.service` 并启用。
3. 迁移应用目录 `/opt/zhihai` → `/opt/shihai`。
4. 迁移部署脚本 `/usr/local/sbin/deploy-zhihai` → `/usr/local/sbin/deploy-shihai`。
5. 迁移 `deploy` 用户 SSH 私钥 `zhihai_deploy_key` → `shihai_deploy_key`。
6. 更新 sudoers 规则 `deploy-zhihai` → `deploy-shihai`。
7. 启动新服务并执行本地健康检查。

脚本幂等，重复执行会跳过已完成步骤；失败时会在 `/opt/shihai-migration-backup/` 保留备份，可据此回滚。执行前建议手动确认以下路径与现有环境一致：

- 应用目录默认 `/opt/zhihai`，如不同请修改脚本顶部变量。
- `deploy` 用户名默认 `deploy`，可通过环境变量 `DEPLOY_USER` 覆盖。
- 健康检查默认 `http://127.0.0.1:8080/api/health`，可通过 `HEALTH_URL` 覆盖。

如果服务器环境与默认值不一致，也可以参照脚本手动迁移。迁移完成后用新脚本验证：

```bash
su - deploy -c 'sudo -n /usr/local/sbin/deploy-shihai --help'
```

## 登录和空间使用

API 使用 Cookie 会话。调试时保存 Cookie：

```bash
curl -c cookies.txt -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"account":"user@example.com","password":"your-password"}'

curl -c cookies.txt -b cookies.txt -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"user@example.com","password":"your-password"}'

curl -b cookies.txt http://localhost:8080/api/auth/me
curl -b cookies.txt http://localhost:8080/api/workspaces
```

空间类型：

- `PERSONAL`：用户个人空间，自动创建。
- `ORG`：组织根空间，需要超级管理员权限创建。组织文档对所有下属团队可见（oversight 整个子树）。
- `TEAM`：团队空间，可由用户创建。建在组织下时需要超管或组织 OWNER 权限；独立创建则任何用户均可。
- `PUBLIC`：公共空间，需要管理员权限创建。公共文档对所有用户的 AI 检索开放。

创建组织根（仅超级管理员）：

```bash
curl -b cookies.txt -X POST http://localhost:8080/api/workspaces/org \
  -H 'Content-Type: application/json' \
  -d '{"name":"sunline"}'
```

在组织下创建子团队（超管或组织 OWNER；`parentId` 可选，不传则创建顶级团队）：

```bash
curl -b cookies.txt -X POST http://localhost:8080/api/workspaces/team \
  -H 'Content-Type: application/json' \
  -d '{"name":"scb","parentId":"<组织空间ID>"}'
```

工作空间层级：组织（ORG）作为根节点，通过 `parent_workspace_id` 自引用外键下挂多个团队（TEAM）。`WorkspaceService.effectiveReadableWorkspaceIds` 计算用户的可读空间集合——组织成员获得自身加全部子孙空间，团队成员获得自身加沿父链的祖先空间。RAG 检索链（向量 + 稀疏）统一按此集合过滤，使组织文档对下属团队可见，团队文档不被其他团队或组织看到。

非成员访问空间会被隐藏为不存在。聊天、文档、会话和检索都按空间隔离。启动时 `WorkspaceHierarchyInitializer`（`@Profile("!test")`）幂等地创建 `sunline` 根组织并把现有无上级团队挂到其下。前端空间切换器按 `parentId` 渲染为树形（组织根 + 下属团队分组）。

## 文档导入

推荐使用异步空间文档流程。上传时必须提供唯一的 `clientRequestId`：

```bash
curl -b cookies.txt -c cookies.txt -X POST \
  'http://localhost:8080/api/documents/upload?workspaceId=personal-1&clientRequestId=upload-001' \
  -F 'file=@docs/example.pdf'
```

上传返回任务信息后，通过任务接口查看处理状态：

```bash
curl -b cookies.txt 'http://localhost:8080/api/document-tasks?workspaceId=personal-1'
curl -b cookies.txt 'http://localhost:8080/api/document-tasks/<taskId>/batches?workspaceId=personal-1'
curl -X POST -b cookies.txt \
  'http://localhost:8080/api/document-tasks/<taskId>/retry?workspaceId=personal-1'
```

文档任务支持批次进度、失败原因和可重试判断。长 PDF 重试时会跳过已成功批次；确定性的页数、尺寸或 OCR 限制错误不能直接重试。

管理员还可以使用旧的同步全局接口：

```bash
curl -b cookies.txt -X POST http://localhost:8080/api/ingest
curl -b cookies.txt -X POST http://localhost:8080/api/documents/rebuild
curl -b cookies.txt -X POST http://localhost:8080/api/documents/sync
```

旧接口和全局导入仅限 `ADMIN` 或 `SUPER_ADMIN`。当前推荐优先使用 `/api/documents/*` 的异步任务接口。

## RAG 问答

普通问答：

```bash
curl -b cookies.txt -X POST http://localhost:8080/api/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"demo","workspaceId":"personal-1","message":"总结当前知识库的主要内容"}'
```

工作台流式问答：

```bash
curl -N -b cookies.txt -X POST http://localhost:8080/api/workbench/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"demo","workspaceId":"personal-1","message":"什么是 RAG？"}'
```

流式接口返回 `text/event-stream`。前端会处理文本片段、来源和工具调用事件。当前 RAG 默认启用混合检索和答案依据校验；知识库没有足够依据时可以使用模型兜底，但默认不连接真实 Web 搜索。

检索诊断（查看某问题命中了哪些分块、得分和可见性过滤结果）：

```bash
curl -b cookies.txt -X POST http://localhost:8080/api/rag/debug \
  -H 'Content-Type: application/json' \
  -d '{"workspaceId":"team-1","message":"总结当前知识库的主要内容"}'
```

检索按当前用户在指定空间下的可读空间集合过滤：组织空间下会覆盖整个子树，团队空间下包含自身加祖先链（含所属组织）。

## 模型配置

每个用户可以独立选择大模型，支持三种模式：

- **跟随默认（FOLLOW_DEFAULT）**：使用管理员设置的全局默认模型。
- **使用池模型（USE_POOL_MODEL）**：从管理员维护的模型池中选择一个已启用的模型。
- **自定义（CUSTOM）**：填写自己的 API 地址、密钥和模型标识。

模型配置 RBAC 边界：

- 用户只能创建和管理**自己的 CHAT 模型**（`/api/model-config/me/pool` 系列）。
- EMBEDDING 模型由超级管理员统一管理。
- 模型池的默认模型设置（`PUT /api/model-config/pool/{id}/default`）仅超级管理员可操作。

管理员通过 `/api/model-config/pool` 维护全局模型池：

```bash
# 查看模型池
curl -b cookies.txt http://localhost:8080/api/model-config/pool

# 添加模型
curl -b cookies.txt -X POST http://localhost:8080/api/model-config/pool \
  -H 'Content-Type: application/json' \
  -d '{"name":"DeepSeek V4","baseUrl":"https://api.siliconflow.cn","apiKey":"sk-xxx","model":"deepseek-ai/DeepSeek-V4-Flash"}'

# 设为默认
curl -b cookies.txt -X PUT http://localhost:8080/api/model-config/pool/1/default
```

用户通过 `/api/model-config/me` 配置自己的模型模式：

```bash
# 查看当前配置
curl -b cookies.txt http://localhost:8080/api/model-config/me

# 设为自定义模型
curl -b cookies.txt -X PUT http://localhost:8080/api/model-config/me \
  -H 'Content-Type: application/json' \
  -d '{"mode":"CUSTOM","name":"我的模型","baseUrl":"https://api.openai.com","apiKey":"sk-xxx","model":"gpt-4o"}'
```

后端通过 ThreadLocal（`ModelConfigContext`）在请求链路中携带用户 ID，`TeachingAgentService` 和 RAG 问答均能按用户配置动态创建对应的 ChatClient。管理员修改池模型后，对应的 ChatClient 缓存会自动刷新。

## 会话和学习记录

Teaching Agent 的当前教学检查和实践状态保存在 PostgreSQL 的 `teaching_attempts` 表中，状态默认保留 30 分钟。服务重启或多实例切换后，仍可以使用原来的 `sessionId`、`checkId` 和 `practiceId` 继续当前教学流程；访问教学接口时会即时清理过期状态。后台清理任务的启用状态、下一次执行时间、执行间隔、租约、最近执行结果都保存在 PostgreSQL 的 `scheduled_jobs` 表中，应用内的 `@Scheduled` 只负责高频唤醒和抢占数据库任务，不决定业务执行周期。同一个检查或实践在提交时使用数据库行锁，多实例同时提交相同答案只会完成一次评分和学习记录写入；提交不同答案仍返回冲突。

聊天历史以 PostgreSQL 的 `chat_conversations` 和 `chat_messages` 为事实来源。RAG 上下文按用户、workspace 和客户端会话标识查询最近消息，JVM 内的 `ConversationMemory` 只保留给旧版测试构造器使用，不作为生产业务状态来源。维护 Agent 的确认动作保存在 `maintenance_pending_actions` 表中，重启后仍可在有效期内确认，且同一确认令牌只能消费一次。

学习资产的持久化边界如下：

| 数据 | PostgreSQL 事实表 | 派生投影 | 说明 |
| --- | --- | --- | --- |
| 普通问答学习记录 | `learning_records` | `docs/learning-records/`、学习记录 Outbox | 默认不进入 RAG 知识库，避免把模型回答直接当成知识事实 |
| Teaching 讲解、CHECK、PRACTICE | `learning_records` | `docs/learning-records/`、学习记录 Outbox | 与普通问答共享学习资产，可按类型、主题和 workspace 查询 |
| 正式笔记 | `formal_notes` | `docs/manual-notes/`、文档索引、分块和向量库 | 经过整理后可以进入 RAG，作为用户或空间知识资产 |
| 学习记录投影事件 | `learning_record_outbox` | Markdown 投影 | 支持租约、重试和多实例 `SKIP LOCKED` 抢占 |
| 正式笔记投影事件 | `formal_note_outbox` | Markdown 与 RAG 投影 | 数据库提交成功后异步执行，投影失败可重试 |

写入链路遵循“事实先提交、派生物异步生成”：业务请求先在 PostgreSQL 中保存学习记录或正式笔记，并在同一事务中写入 Outbox 事件；数据库调度器负责周期性抢占事件。文件、`document_indexes`、`document_chunks` 和 Chroma 出现故障时，不回滚已经提交的业务事实，而是记录失败并等待重试。应用本地 `docs/` 目录目前仍用于兼容导出和开发环境投影，生产环境应使用持久化卷或对象存储，并单独持久化检索副本。

会话接口：

```text
GET    /api/conversations
GET    /api/conversations/{conversationId}/messages
DELETE /api/conversations/{conversationId}
POST   /api/conversations/{conversationId}/stop
```

学习记录接口：

```text
GET    /api/learning-records?workspaceId=<workspaceId>
GET    /api/learning-records/teaching-progress?workspaceId=<workspaceId>
GET    /api/learning-records/{date}?workspaceId=<workspaceId>
PUT    /api/learning-records/{date}?workspaceId=<workspaceId>
DELETE /api/learning-records/{date}?workspaceId=<workspaceId>
POST   /api/learning-records/{date}/promote?workspaceId=<workspaceId>
```

学习记录接口都要求登录用户提供当前 `workspaceId`。服务端会先校验用户是否有该知识空间的访问权限，再只返回、修改、删除或提升该空间的记录。数据库中的 `workspace_id` 是隔离依据；Markdown 只按用户、workspace 和日期生成可读投影，不是权限事实源。旧记录导入为 `LEGACY` 后保留空 workspace，不会被自动归属到任何空间。

提升正式笔记时，可选提交 JSON：

```json
{
  "content": "整理后的学习记录内容"
}
```

## API 总览

| 模块 | 主要接口 | 说明 |
| --- | --- | --- |
| 认证 | `/api/auth/*` | 注册、登录、资料、头像、注销 |
| 用户管理 | `/api/admin/users/*` | 管理员查看用户和调整角色 |
| 空间 | `/api/workspaces/*` | 创建空间（个人 / 组织根 / 团队 / 公共）、层级挂载、成员管理、审计 |
| 文档 | `/api/documents/*` | 上传、导入、同步、重建、删除 |
| 文档任务 | `/api/document-tasks/*` | 查询、批次、重试、源文件 |
| 会话 | `/api/conversations/*` | 会话列表、消息、停止、删除 |
| 模型配置 | `/api/model-config/*` | 全局模型池管理（管理员）、用户自管 CHAT 模型池、用户模型模式配置 |
| 问答 | `/api/rag/chat`、`/api/workbench/chat*` | 普通和 SSE 流式问答 |
| 检索诊断 | `/api/rag/debug` | 输入问题查看命中分块、检索得分与可见性 |
| 评测 | `/api/eval/*` | 题库、运行、结果和导入文件 |
| 记录 | `/api/learning-records/*` | 学习记录和正式笔记 |
| 状态 | `/api/health`、`/actuator/*` | 服务和依赖状态 |

## 评测

确定性评测脚本实际位于：

```bash
bash eval/run-deterministic-evals.sh
```

它只运行不依赖真实模型和外部服务的规则测试。仓库不存在 `scripts/run-evals.sh`，不要使用旧 README 中的命令。

前端评测页面支持题库导入、标准/增强检索对比和历史运行查看。模板位于：

```text
eval/templates/
```

真实模型评测需要 PostgreSQL、Chroma、模型 API Key 和评测数据：

```bash
mvn -DskipTests package
java -jar target/my-knowledge-assistant-0.0.1-SNAPSHOT.jar --app.mode=eval
```

## 测试和检查

后端全量测试：

```bash
mvn test
```

前端生产构建：

```bash
cd frontend
yarn build
```

仓库当前没有独立的前端单元测试、Lint 或 TypeScript 检查命令。代码修改后建议至少执行以上两项。

## 数据库、日志和监控

当前配置使用 Flyway 基线和 `spring.jpa.hibernate.ddl-auto=validate`。数据库结构必须由 Flyway 迁移脚本提供，JPA 只在启动时校验结构，不会自动修改生产数据库。生产环境升级前应先备份数据库，并确认新增迁移已成功执行。

学习资产相关迁移包括：

```text
V5__create_learning_records_and_outbox.sql
  learning_records、learning_record_outbox、formal_notes、formal_note_outbox
V6__complete_learning_projection_schema.sql
  为既有 learning_record_outbox 补充 workspace_id，并初始化 formal-note-projection 调度任务
```

模型配置和工作空间层级相关迁移：

```text
V11__create_model_config.sql        模型池与用户模型配置表
V12__add_ai_models_type.sql         模型增加 CHAT/EMBEDDING 类型
V19__add_ai_model_owner.sql         模型增加所有者（用户自管 CHAT 模型的基础）
V20__create_eval_tables.sql         评测题库、运行和结果表
V21__add_workspace_parent.sql       workspaces 增加 parent_workspace_id 自引用外键
V22__allow_org_workspace_type.sql   放宽 workspaces.type 检查约束以包含 ORG 组织类型
```

V22 是一个修复性迁移：`workspaces.type` 列存在仅允许 `PERSONAL/TEAM/PUBLIC` 的 CHECK 约束（在 Flyway 之外手工创建），导致 `WorkspaceHierarchyInitializer` 插入 `ORG` 类型记录时触发约束冲突、应用启动失败。V22 删除旧约束并重建为包含 ORG 的四值约束。已部署环境如果存在同名约束，升级到本版本前应先备份数据库。

数据库事实写入和 Outbox 写入必须保持在同一事务中。投影 worker 使用 `scheduled_jobs` 管理周期和租约，并使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 选择待处理事件；真实 PostgreSQL、多实例抢占、迁移升级和故障重试仍需在部署前完成集成验证。

运行时目录：

```text
data/                 头像和评测导入文件
docs/workspaces/      空间上传源文件
logs/                 Log4j2 日志
target/               Maven 构建产物
```

Actuator 默认绑定 `127.0.0.1:8081`，仅暴露健康和信息端点：

```text
/actuator/health
/actuator/info
```

日志会记录请求 ID、接口耗时、任务阶段、模型调用和错误摘要，并自动脱敏密码、Token、Authorization 等字段。不要把真实 API Key、数据库密码或生产数据提交到仓库。

单服务器不部署独立日志平台。普通运行日志写入 PostgreSQL `system_log` 和本地滚动文本文件，由维护页面查询、过滤和清理；审计日志继续写入独立的 `audit_events` 和 `audit_purge_events`。

## 搜索和 Agent 扩展

项目保留 `WebSearchService`、工具调用事件和任务状态接口，便于继续学习 Agent。当前联网搜索默认关闭，`WebSearchService` 不是已接入的商业搜索服务。建议在现有只读接口基础上先实现知识库维护 Agent，再逐步加入需要确认的同步、重试和删除操作。

## 目录结构

```text
src/main/java/com/example/workbench/  后端领域代码
src/main/resources/                  配置、日志和数据库迁移
src/test/java/                       后端测试
frontend/src/                        Vue 前端
eval/                                 评测数据、模板和确定性评测脚本
docs/                                 知识库源文档、投影文件和设计资料
.github/workflows/                   CI 和真实模型评测工作流
```

## 常见问题

### Chroma collection 不存在

确认 Chroma 地址、租户、数据库和 collection 配置正确，并确认应用拥有创建 collection 的权限。默认 collection 名称为 `knowledge_assistant`。

### 模型调用失败

先检查用户级模型配置：前端「设置 → 模型配置」或 `/api/model-config/me` 确认当前模式（跟随默认 / 池模型 / 自定义）。自定义模式下需确保 API Key、base URL 和模型标识正确且模型可用。

模型服务商错误已通过 `ModelProviderException` 统一转为中文提示（如「模型调用频率受限，请稍后重试」「模型配额已用尽，请联系管理员或切换模型」等），不会把厂商原始错误码直接暴露给用户；响应中保留原始错误码和 traceId 便于排查。

全局默认模型通过 `/api/model-config/pool` 查看；管理员可在此检查默认模型的启用状态和 API 配置。

最后确认 PostgreSQL、Chroma 和 `application.properties` 中的兜底模型参数正常。应用会在可配置范围内重试或使用本地保守回答，但这不代表外部模型配置已经正常。

### OCR 失败

确认 `tesseract --version` 可执行，并安装 `chi_sim` 和 `eng` 语言包。超长 PDF 还需要检查页数、OCR 页数、页面尺寸和像素限制。

### 前端请求了错误的后端

检查 `frontend/vite.config.mjs` 的代理目标。当前代理目标不是由 `VITE_*` 环境变量自动控制；需要修改配置文件后重启 Vite。
