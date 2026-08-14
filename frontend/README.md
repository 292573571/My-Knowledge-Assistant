# 智海学习助手前端

Vue + Vite 实现的统一学习工作台，支持普通 RAG 问答、主题教学、CHECK/PRACTICE、SSE 事件、来源引用、Markdown 渲染和服务端会话历史。

## 截图

当前仓库未提交截图文件。建议后续将页面截图放到：

```text
docs/screenshots/workbench.png
```

并在这里引用：

```md
![Personal AI Workbench](docs/screenshots/workbench.png)
```

## 功能

- 左侧会话列表、中间对话区和右侧学习进度面板
- 新建、切换、删除会话
- 会话历史保存到 PostgreSQL
- HttpOnly Cookie 会话认证，浏览器 JavaScript 不接触 Token
- 普通回答和主题教学统一使用 `/api/learning-assistant` 接口
- SSE 事件输出、EOF 异常检测、停止和组件卸载取消
- Sources 逐步展示和点击展开
- Tool Calls 逐步展示和点击展开
- Markdown 渲染
- 代码高亮
- 结构化错误提示、请求 ID 和停止生成

## 本地启动

安装依赖：

```bash
yarn install --frozen-lockfile
```

启动开发服务：

```bash
yarn dev
```

默认前端地址：

```text
http://localhost:5173
```

构建生产包：

```bash
yarn build
```

预览生产包：

```bash
yarn preview
```

## 后端代理

Vite 开发服务器会把 `/api` 代理到后端：

```js
// vite.config.js
proxy: {
  '/api': {
    target: 'http://localhost:8080',
    changeOrigin: true
  }
}
```

请确保后端服务运行在：

```text
http://localhost:8080
```

## 接口说明

### 非流式聊天

普通聊天模式调用：

```http
POST /api/chat
```

请求：

```json
{
  "message": "MCP 是什么？"
}
```

响应：

```json
{
  "answer": "..."
}
```

### 非流式 RAG 问答

知识库模式调用：

```http
POST /api/rag/chat
```

请求：

```json
{
  "conversationId": "default",
  "message": "MCP 和 Tool Calling 有什么区别？"
}
```

响应：

```json
{
  "answer": "...",
  "sources": [
    {
      "file": "mcp.md",
      "chunkIndex": 2,
      "snippet": "MCP 是一种协议...",
      "score": 0.86
    }
  ],
  "toolCalls": [
    {
      "toolName": "read_file",
      "arguments": { "path": "mcp.md" },
      "resultPreview": "...",
      "success": true,
      "durationMs": 300
    }
  ]
}
```

### SSE 流式聊天

流式接口调用：

```http
POST /api/workbench/chat/stream
Content-Type: application/json

{"conversationId":"default","mode":"rag","message":"..."}
```

前端监听事件：

```text
start
token
source
tool_call_start
tool_call_result
done
error
```

`token` 事件示例：

```text
event: token
data: {"text":"MCP"}
```

`source` 事件示例：

```text
event: source
data: {"file":"mcp.md","chunkIndex":2,"snippet":"MCP 是一种协议...","score":0.86}
```

`tool_call_start` 事件示例：

```text
event: tool_call_start
data: {"toolName":"read_file","arguments":{"path":"mcp.md"}}
```

`tool_call_result` 事件示例：

```text
event: tool_call_result
data: {"toolName":"read_file","success":true,"durationMs":300,"resultPreview":"..."}
```

`done` 事件示例：

```text
event: done
data: {}
```

流式请求通过 `fetch` 读取 `text/event-stream`，身份由 HttpOnly Cookie 携带。Token 和完整问题不会出现在 URL 中。

## 工作空间与学习记录

学习记录页面的请求都绑定当前工作空间。以下接口必须通过查询参数提供 `workspaceId`，服务端会先校验访问权限，再只处理该空间的记录：

```text
GET    /api/learning-records?workspaceId=<workspaceId>
GET    /api/learning-records/teaching-progress?workspaceId=<workspaceId>
GET    /api/learning-records/{date}?workspaceId=<workspaceId>
PUT    /api/learning-records/{date}?workspaceId=<workspaceId>
DELETE /api/learning-records/{date}?workspaceId=<workspaceId>
POST   /api/learning-records/{date}/promote?workspaceId=<workspaceId>
```

提升正式笔记时，请求体可携带当前页面编辑后的内容：

```json
{
  "content": "整理后的学习记录内容"
}
```

`workspaceId` 不应从用户可编辑的 Markdown 内容中信任。客户端只提交当前工作空间标识，记录和正式笔记的 workspace 归属由服务端校验并写入。

## 前端状态持久化

会话和消息以 PostgreSQL 为唯一持久数据源；浏览器 `localStorage` 不保存 Token、聊天内容或知识库资料。

## 目录结构

```text
src/
├── api/                     API 请求、认证、工作空间和统一学习助手 SSE
├── components/
│   ├── ChatInput.vue
│   ├── ChatMessage.vue
│   ├── ConversationSidebar.vue
│   ├── LearningAssistantPage.vue
│   ├── InfoPanel.vue
│   ├── LoadingDots.vue
│   ├── SourcePanel.vue
│   └── ToolCallPanel.vue
├── styles/
│   └── main.css
├── utils/
│   └── markdown.js
├── App.vue
└── main.js
```

统一助手组件只在当前工作空间下加载会话。会话元数据、消息和教学待办由后端恢复，浏览器不把聊天内容写入 `localStorage`。旧版双轨聊天入口已经移除，不再作为前端入口。

## 常见问题

如果页面显示请求失败，请检查：

- 后端是否启动在 `localhost:8080`
- Vite dev server 是否正在运行
- 后端是否实现当前模式对应接口
- SSE 接口是否返回标准 `text/event-stream`

如果 `/api/learning-assistant/sessions/{sessionId}/messages/stream` 返回 404，说明前后端版本不一致；请确认后端已包含统一学习助手接口。
