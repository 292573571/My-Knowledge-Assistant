# AGENTS.md

## 项目概述

识海学习助手（My Knowledge Assistant）——基于 Spring Boot 3 + Spring AI 的个人知识管理助手。

## 构建和测试命令

### 后端

```bash
# 编译
/Users/95h/apache-maven-3.6.3/bin/mvn compile

# 运行测试
/Users/95h/apache-maven-3.6.3/bin/mvn test

# 打包（跳过测试）
/Users/95h/apache-maven-3.6.3/bin/mvn package -DskipTests
```

### 前端

```bash
cd frontend
yarn install
yarn build
yarn dev    # 开发模式
```

### 部署

```bash
DEPLOY_HOST=<服务器地址> ./deploy/deploy-shihai --yes
```

## 技术栈

- **后端**：Spring Boot 3.4.5, Spring AI 1.0.0, PostgreSQL, Chroma, Log4j2
- **前端**：Vue 3, Vite, markdown-it, DOMPurify, highlight.js
- **数据库迁移**：Flyway（V1-V13）
- **AI 模型**：OpenAI 兼容接口（硅基流动 SiliconFlow）

## 项目约定

- 使用简体中文交流
- 不添加注释，除非用户要求
- API Key 等敏感信息不硬编码，通过环境变量传入
- 数据库变更通过 Flyway migration 脚本（非 ddl-auto）
- 测试必须通过：`mvn test` 300 个测试全过
- 前端构建必须通过：`yarn build`
