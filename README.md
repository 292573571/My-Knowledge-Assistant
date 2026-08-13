# My Knowledge Assistant

一个面向个人学习和团队知识管理的 RAG 知识助手。项目使用 Spring Boot、Spring AI、PostgreSQL、Chroma 和 Vue，支持多用户知识空间、文档异步导入、混合检索、多轮对话、流式回答、评测和基础可观测性。

当前版本已经适合作为 RAG 应用和 Agent 学习底座，但联网搜索仍是扩展点，不应当按完整搜索产品理解。

## 功能概览

- 用户注册、登录、Cookie 会话、资料和头像管理。
- 个人、团队和公共知识空间，以及 `OWNER`、`EDITOR`、`VIEWER` 权限。
- Markdown、TXT、HTML、DOCX、PDF、PNG、JPG/JPEG 导入。
- PDF 文本层、扫描页 OCR 和混合 PDF 逐页处理。
- 长 PDF 分批解析、批次状态、失败清理和成功批次跳过恢复。
- Chroma 向量检索、PostgreSQL 稀疏检索、混合检索和 RRF 排序。
- 多轮聊天、SSE 流式回答、会话保存、停止和删除。
- 来源页码展示、答案依据校验、模型兜底和工具调用记录。
- 评测题库、规则评测、检索指标、运行记录和质量门禁。
- Actuator、Prometheus、请求 ID、结构化日志和敏感信息脱敏。

当前默认关闭联网搜索：`workbench.rag.web-search.enabled=false`。`WebSearchService` 是搜索扩展点，需要接入真实搜索服务后再开启。

## 技术栈

- 后端：Java 17、Spring Boot 3.4.5、Spring AI 1.0.0、Spring Data JPA。
- 数据库：PostgreSQL、Flyway、Hibernate Schema Update。
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

默认模型和服务配置在 `src/main/resources/application.properties`：

```properties
spring.ai.openai.chat.options.model=deepseek-ai/DeepSeek-V4-Flash
spring.ai.openai.embedding.options.model=BAAI/bge-m3
spring.ai.openai.chat.base-url=https://api.siliconflow.cn
spring.ai.openai.embedding.base-url=https://api.siliconflow.cn
```

当前默认 Chroma 地址是配置文件中的远程地址。连接本地 Chroma 时覆盖：

```bash
export SPRING_AI_VECTORSTORE_CHROMA_CLIENT_HOST='http://localhost'
export SPRING_AI_VECTORSTORE_CHROMA_CLIENT_PORT='8000'
```

常用可选变量：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `ADMIN_ACCOUNTS` | 空 | 启动时把已有账号迁移为 `ADMIN` |
| `AUTH_COOKIE_SECURE` | `false` | HTTPS 环境设为 `true` |
| `AVATAR_DIRECTORY` | `data/avatars` | 头像目录 |
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

前端默认地址：`http://localhost:5173`。开发代理目标由 `frontend/vite.config.js` 配置；修改该文件后，才能把 `/api` 请求切换到本地后端。

生产构建：

```bash
cd frontend
yarn build
yarn preview
```

## 发版与启动脚本

项目只保留一个发版入口：

```bash
./deploy/deploy-zhihai
```

它会在本地完成后端测试打包、前端构建、上传发布包，然后在服务器上停止并启动 `zhihai` 服务，执行服务器本地和公网健康检查。服务器部署端使用同一个脚本的内部子命令，不再维护第二份启动逻辑。

首次配置或更新服务器部署脚本时，在本地项目根目录执行一次：

```bash
scp -i "$DEPLOY_KEY" -P "$DEPLOY_PORT" deploy/deploy-zhihai "$DEPLOY_USER@$DEPLOY_HOST:/tmp/deploy-zhihai"
ssh -tt -i "$DEPLOY_KEY" -p "$DEPLOY_PORT" "$DEPLOY_USER@$DEPLOY_HOST" \
  "sudo install -m 750 /tmp/deploy-zhihai /usr/local/sbin/deploy-zhihai && rm -f /tmp/deploy-zhihai"
```

并为 `deploy` 用户配置只允许执行这个固定脚本的免密 sudo，例如：

```text
deploy ALL=(root) NOPASSWD: /usr/local/sbin/deploy-zhihai *
```

服务器预检会分别检查 `rsync` 和 sudo 权限；如果失败，会明确提示是缺少 `rsync`，还是 sudoers 不允许执行部署脚本。服务器上可以分别验证：

```bash
command -v rsync
su - deploy -c 'sudo -n /usr/local/sbin/deploy-zhihai --help'
```

发版脚本不会要求 `sudo -n true`，也不会要求 `deploy` 用户免密执行任意 root 命令。后续发版只上传发布包并调用这个固定部署脚本；修改部署脚本本身时，需要重新手工安装一次。脚本会在发版前检查服务器脚本是否支持 `remote` 和 `cleanup` 子命令。

只有服务器本地健康检查和公网健康检查都成功后，脚本才会删除旧的 release 和备份；任一检查失败时，旧版本仍会保留用于回滚。

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
- `TEAM`：团队空间，可由用户创建。
- `PUBLIC`：公共空间，需要管理员权限创建。

非成员访问空间会被隐藏为不存在。聊天、文档、会话和检索都按空间隔离。

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

## 会话和学习记录

会话接口：

```text
GET    /api/conversations
GET    /api/conversations/{conversationId}/messages
DELETE /api/conversations/{conversationId}
POST   /api/conversations/{conversationId}/stop
```

学习记录接口：

```text
GET    /api/learning-records
GET    /api/learning-records/{date}
PUT    /api/learning-records/{date}
DELETE /api/learning-records/{date}
POST   /api/learning-records/{date}/promote
```

## API 总览

| 模块 | 主要接口 | 说明 |
| --- | --- | --- |
| 认证 | `/api/auth/*` | 注册、登录、资料、头像、注销 |
| 用户管理 | `/api/admin/users/*` | 管理员查看用户和调整角色 |
| 空间 | `/api/workspaces/*` | 创建空间、成员管理、审计 |
| 文档 | `/api/documents/*` | 上传、导入、同步、重建、删除 |
| 文档任务 | `/api/document-tasks/*` | 查询、批次、重试、源文件 |
| 会话 | `/api/conversations/*` | 会话列表、消息、停止、删除 |
| 问答 | `/api/rag/chat`、`/api/workbench/chat*` | 普通和 SSE 流式问答 |
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

仓库当前没有独立的前端单元测试、Lint 或 TypeScript 检查命令。代码修改后建议至少执行以上两项和：

```bash
```

## 数据库、日志和监控

当前配置同时启用 Flyway 基线和 `spring.jpa.hibernate.ddl-auto=update`。部分结构由迁移脚本提供，JPA 实体仍会在启动时更新结构；生产环境上线前应明确迁移边界，并评估关闭 `ddl-auto=update`。

运行时目录：

```text
data/                 头像和评测导入文件
docs/workspaces/      空间上传源文件
logs/                 Log4j2 日志
target/               Maven 构建产物
```

Actuator 默认绑定 `127.0.0.1:8081`，暴露：

```text
/actuator/health
/actuator/info
/actuator/prometheus
```

日志会记录请求 ID、接口耗时、任务阶段、模型调用和错误摘要，并自动脱敏密码、Token、Authorization 等字段。不要把真实 API Key、数据库密码或生产数据提交到仓库。

## 搜索和 Agent 扩展

项目保留 `WebSearchService`、工具调用事件和任务状态接口，便于继续学习 Agent。当前联网搜索默认关闭，`WebSearchService` 不是已接入的商业搜索服务。建议在现有只读接口基础上先实现知识库维护 Agent，再逐步加入需要确认的同步、重试和删除操作。

## 目录结构

```text
src/main/java/com/example/workbench/  后端领域代码
src/main/resources/                  配置、日志和数据库迁移
src/test/java/                       后端测试
frontend/src/                        Vue 前端
eval/                                 评测数据、模板和确定性评测脚本
docs/                                 知识库源文档和设计资料
.github/workflows/                   CI 和真实模型评测工作流
```

## 常见问题

### Chroma collection 不存在

确认 Chroma 地址、租户、数据库和 collection 配置正确，并确认应用拥有创建 collection 的权限。默认 collection 名称为 `knowledge_assistant`。

### 模型调用失败

确认 PostgreSQL、Chroma、聊天模型 API Key、Embedding API Key 和 OpenAI-compatible base URL 均正确。应用会在可配置范围内重试或使用本地保守回答，但这不代表外部模型配置已经正常。

### OCR 失败

确认 `tesseract --version` 可执行，并安装 `chi_sim` 和 `eng` 语言包。超长 PDF 还需要检查页数、OCR 页数、页面尺寸和像素限制。

### 前端请求了错误的后端

检查 `frontend/vite.config.js` 的代理目标。当前代理目标不是由 `VITE_*` 环境变量自动控制；需要修改配置文件后重启 Vite。
