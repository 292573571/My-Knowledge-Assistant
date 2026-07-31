# Personal AI Workbench Frontend

Vue + Vite 实现的个人 AI 工作台前端，支持普通聊天、RAG 问答、SSE 流式输出、来源引用、工具调用面板、Markdown 渲染和服务端会话历史。

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

- 左侧深色会话列表
- 中间白色聊天窗口
- 右侧浅灰信息面板
- 新建、切换、删除会话
- 会话历史保存到 PostgreSQL
- HttpOnly Cookie 会话认证，浏览器 JavaScript 不接触 Token
- 普通非流式接口调用
- SSE 流式 token 输出
- Sources 逐步展示和点击展开
- Tool Calls 逐步展示和点击展开
- Markdown 渲染
- 代码高亮
- 错误提示和停止生成

## 本地启动

安装依赖：

```bash
npm install
```

启动开发服务：

```bash
npm run dev
```

默认前端地址：

```text
http://localhost:5173
```

构建生产包：

```bash
npm run build
```

预览生产包：

```bash
npm run preview
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

## 前端状态持久化

会话和消息以 PostgreSQL 为唯一持久数据源；浏览器 `localStorage` 不保存 Token、聊天内容或知识库资料。

## 目录结构

```text
src/
├── api/
│   ├── chatApi.js
│   └── streamApi.js
├── components/
│   ├── ChatInput.vue
│   ├── ChatLayout.vue
│   ├── ChatMessage.vue
│   ├── ConversationSidebar.vue
│   ├── InfoPanel.vue
│   ├── LoadingDots.vue
│   ├── SourcePanel.vue
│   └── ToolCallPanel.vue
├── stores/
│   └── chatStore.js
├── styles/
│   └── main.css
├── utils/
│   └── markdown.js
├── App.vue
└── main.js
```

## 常见问题

如果页面显示请求失败，请检查：

- 后端是否启动在 `localhost:8080`
- Vite dev server 是否正在运行
- 后端是否实现当前模式对应接口
- SSE 接口是否返回标准 `text/event-stream`

如果 `/api/workbench/chat/stream` 返回 404，说明后端还没有实现流式接口；非流式 RAG 可先使用 `/api/rag/chat`。
