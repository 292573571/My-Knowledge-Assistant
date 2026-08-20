# 识海生产日志平台

本目录提供最低成本的运行日志方案：

```text
Spring Boot -> 本地 JSON 滚动日志 -> Grafana Alloy -> Loki -> Grafana
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

镜像版本已固定在 `docker-compose.yml`，升级前应先备份 `loki-data` 和 `grafana-data`。

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
APP_ENVIRONMENT=production
INSTANCE_ID=server-01
```

如果 Loki 在独立观测服务器，将 `LOKI_URL` 改为内网 HTTPS 地址，并在反向代理或 Loki 配置中增加认证。

安装服务：

```bash
install -o root -g root -m 644 deploy/observability/alloy/alloy-shihai.service /etc/systemd/system/alloy-shihai.service
systemctl daemon-reload
systemctl enable --now alloy-shihai
systemctl status alloy-shihai --no-pager
```

Alloy 只读取 `/opt/shihai/logs/my-knowledge-assistant.json.log`，不会读取 PostgreSQL，也不会采集审计表。

## 3. 应用配置

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

## 4. Grafana 数据源

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

## 5. 保留策略

- 普通运行日志：由应用本地文件轮转和 Loki `retention_period` 管理。
- 应用 PostgreSQL `system_log`：继续按现有定时策略清理。
- 审计日志：不进入 Loki，不执行普通日志清理策略。
- 审计日志删除：只允许超级管理员通过应用接口执行，并写入 `audit_purge_events`。
- 生产审计归档：建议在数据库备份之外，再导出到加密对象存储或 WORM 存储。

Promtail 已于 2026-03-02 结束生命周期，本方案使用 Grafana Alloy，不使用 Promtail。
