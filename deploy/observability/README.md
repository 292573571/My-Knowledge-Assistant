# 识海生产日志平台

本目录提供最低成本的运行日志方案：

```text
Spring Boot -> 本地 JSON 滚动日志 -> Grafana Alloy -> Loki -> Grafana
Spring Boot + OpenTelemetry Java Agent -> Grafana Alloy OTLP -> Tempo -> Grafana
Spring Boot Actuator Prometheus -> Prometheus -> Grafana
```

审计日志不发送到 Loki。`audit_events` 和 `audit_purge_events` 仍以 PostgreSQL 为权威存储，后续应按生产留存要求归档到对象存储或 WORM 存储。

## 1. 启动 Loki 和 Grafana

在日志平台服务器执行：

```bash
cd deploy/observability
export GRAFANA_ADMIN_USER=admin
export GRAFANA_ADMIN_PASSWORD='change-this-in-production'
docker compose up -d
```

当前 Compose 只绑定 `127.0.0.1`，生产环境应通过已有 HTTPS 反向代理暴露 Grafana，并禁止直接暴露 Loki 的 3100 端口。

镜像版本已固定在 `docker-compose.yml`，升级前应先备份 `loki-data`、`tempo-data`、`prometheus-data` 和 `grafana-data`。

## 2. 配置 Alloy

在应用服务器安装与发行版匹配的 Grafana Alloy，创建配置和环境文件：

```bash
install -o root -g root -m 640 deploy/observability/alloy/config.alloy /etc/alloy/config.alloy
install -d -o alloy -g alloy -m 750 /var/lib/alloy
install -d -o root -g root -m 750 /etc/shihai
```

创建 `/etc/shihai/alloy.env`，不要提交到仓库：

```bash
LOKI_URL=http://127.0.0.1:3100/loki/api/v1/push
TEMPO_OTLP_ENDPOINT=127.0.0.1:4317
PROMETHEUS_REMOTE_WRITE_URL=http://127.0.0.1:9090/api/v1/write
APP_ENVIRONMENT=production
INSTANCE_ID=server-01
```

如果 Loki 在独立观测服务器，将 `LOKI_URL` 改为内网 HTTPS 地址，并在反向代理或 Loki 配置中增加认证。

Compose 环境变量还需要指定应用的 Prometheus 地址：

```bash
export SHIHAI_METRICS_TARGET=host.docker.internal:8081
```

Linux 服务器上通常将它改为应用服务器的内网地址，例如 `10.0.0.12:8081`，并确保应用管理端口只对 Prometheus 所在内网开放，不要暴露到公网。

应用的 `MANAGEMENT_SERVER_ADDRESS` 不能继续使用 `127.0.0.1`，需要绑定到应用服务器内网地址或由 Prometheus 通过本机网络访问。例如：

```bash
MANAGEMENT_SERVER_ADDRESS=10.0.0.12
```

安装服务：

```bash
install -o root -g root -m 644 deploy/observability/alloy/alloy-shihai.service /etc/systemd/system/alloy-shihai.service
systemctl daemon-reload
systemctl enable --now alloy-shihai
systemctl status alloy-shihai --no-pager
```

Alloy 只读取 `/opt/shihai/logs/my-knowledge-assistant.json.log`，并接收应用的 OTLP Trace/Metrics；不会读取 PostgreSQL，也不会采集审计表。

## 3. 安装 OpenTelemetry Java Agent

应用的 Trace 由 OpenTelemetry Java Agent 自动埋点产生，覆盖 Spring MVC、HTTP Client、JDBC 和异步上下文。Agent 不打包进应用仓库，需要在服务器单独下载并校验版本：

```bash
install -d -o shihai -g shihai -m 750 /opt/shihai/otel
curl --fail --location --output /opt/shihai/otel/opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.12.0/opentelemetry-javaagent.jar
```

为 `shihai` systemd 服务增加环境变量。推荐使用 `systemctl edit shihai`：

```ini
[Service]
EnvironmentFile=/etc/shihai/shihai-otel.env
```

将 `deploy/observability/systemd/shihai-otel.env.example` 复制为 `/etc/shihai/shihai-otel.env`，并按实际实例修改 `INSTANCE_ID`、`OTEL_SERVICE_NAME` 和 OTLP 地址。模板由 Java Agent 导出 OTel Metrics，并由 Prometheus 抓取 Spring Boot Micrometer 指标；不要同时打开 `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED`，避免同一指标重复上报。然后执行：

```bash
systemctl daemon-reload
systemctl restart shihai
```

默认 Trace 链路为：

```text
HTTP 请求 -> Spring Controller -> 异步任务 -> HTTP/模型调用 -> JDBC
```

HTTP 响应中的 `X-Trace-Id`、MDC 中的 `traceId`、JSON 日志中的 `traceId` 与 Tempo Trace ID 使用同一个 32 位十六进制值。异步线程通过 OTel `Context` 和 MDC 同时传播，日志可以按 Trace ID 关联完整请求链路。

跨服务调用优先使用标准 W3C `traceparent` 请求头。应用仍返回 `X-Trace-Id` 方便排障和前端反馈；没有 Java Agent 时，应用会使用兼容的 `X-Trace-Id` 值写入日志，但不会产生完整 Tempo Span。

## 4. 应用配置

应用新增 JSON 文件日志：

```text
${LOG_PATH}/my-knowledge-assistant.json.log
```

该文件与现有文本日志一样按天和大小轮转，默认保留 14 个归档。`LOG_PATH` 在生产环境应设置为 `/opt/shihai/logs`，并确保 `alloy` 用户拥有目录读取权限：

```bash
install -d -o shihai -g alloy -m 750 /opt/shihai/logs
setfacl -m u:alloy:rx /opt/shihai/logs
setfacl -m u:alloy:r /opt/shihai/logs/my-knowledge-assistant.json.log
```

Loki 只将以下字段作为标签，避免高基数：

```text
level
environment
instance_id
```

`requestId`、`traceId`、`userId`、`workspaceId` 等字段作为结构化元数据，不作为 Loki 标签。

## 5. Grafana 数据源

首次登录 Grafana 后添加 Loki 数据源：

```text
URL: http://loki:3100
```

查询示例：

```logql
{service="shihai", environment="production"}
```

```logql
{service="shihai", environment="production"} | json | level="ERROR"
```

在 Tempo Trace 的 Logs 面板中，Grafana 会使用 `traceId` 结构化字段自动跳转到同一请求的 Loki 日志；也可以直接查询：

```logql
{service="shihai", environment="production"} | json | traceId="${traceId}"
```

## 6. 保留策略

- 普通运行日志：由应用本地文件轮转和 Loki `retention_period` 管理。
- 应用 PostgreSQL `system_log`：继续按现有定时策略清理。
- 审计日志：不进入 Loki，不执行普通日志清理策略。
- 审计日志删除：只允许超级管理员通过应用接口执行，并写入 `audit_purge_events`。
- 生产审计归档：建议在数据库备份之外，再导出到加密对象存储或 WORM 存储。

Promtail 已于 2026-03-02 结束生命周期，本方案使用 Grafana Alloy，不使用 Promtail。
