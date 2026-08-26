# Runbook：服务器安装并接入 Redis

适用环境（已实测确认）：

| 项 | 实际值 |
| --- | --- |
| 主机 | 腾讯云 Lighthouse `175.178.229.209`（实例 `lhins-74yhsbol`，ap-guangzhou） |
| 操作系统 | OpenCloudOS 9.6 / x86_64 |
| 可用软件源 | AppStream 自带 `redis 7.2.15-1.oc9`（无需第三方 repo、无需编译） |
| 内存 | 总 3655 MB；现有占用 JVM ≤1024 MB + Chroma ~200 MB + PostgreSQL ~52 MB + 宝塔面板 ~59 MB |
| 6379 端口 | 安装前空闲，此前未装过 Redis |
| 应用环境变量 | `EnvironmentFile=/etc/shihai/shihai.env`（`shihai.service` 读取，权限 600 root） |

Redis 在本项目中的定位：**流式回答的 chunk 缓冲 + 幂等结果缓存**，属于可重建的易失数据。
因此配置上刻意**关闭持久化**、设置内存硬上限、只监听本机 —— 既省内存又省 IO，且不引入新的数据备份负担。

---

## 一、安装（root 执行）

```bash
sudo dnf install -y redis
rpm -q redis          # 期望输出 redis-7.2.15-1.oc9.x86_64
```

安装后不要直接启动，先改配置（默认配置无密码，属于高危）。

## 二、配置

配置文件路径：`/etc/redis/redis.conf`。先备份，再改 6 项。

```bash
sudo cp /etc/redis/redis.conf /etc/redis/redis.conf.bak.$(date +%Y%m%d)
```

### 2.1 生成密码

```bash
REDIS_PASS=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)
echo "生成的 Redis 密码：$REDIS_PASS"      # 记下来，后面写进 shihai.env
```

> 只用字母数字，避免密码里出现 `#` `"` `$` 等字符导致 redis.conf 与 properties 转义踩坑。

### 2.2 写入配置

```bash
sudo tee -a /etc/redis/redis.conf > /dev/null <<EOF

# ===== shihai 流式缓冲专用配置（追加于文件末尾，覆盖前面的同名项）=====
# 只监听本机回环，绝不暴露公网
bind 127.0.0.1 -::1
protected-mode yes
port 6379

# 认证
requirepass ${REDIS_PASS}

# 内存硬上限：与 JVM(1G) / Chroma / PG 共存，留足余量
maxmemory 256mb
# 流式缓冲的 key 全部带 TTL，只淘汰这类 key，不会误删无 TTL 数据
maxmemory-policy volatile-lru

# 易失数据，关闭持久化：省 IO、省内存、无需备份
save ""
appendonly no

# 长连接保活，配合 SSE 心跳
tcp-keepalive 300
timeout 0
EOF
```

### 2.3 校验配置语法

```bash
sudo redis-server /etc/redis/redis.conf --test-memory 0 2>/dev/null; echo "exit=$?"
# 或直接看关键项是否落到文件末尾：
sudo tail -20 /etc/redis/redis.conf
```

## 三、启动并设置开机自启

```bash
sudo systemctl enable --now redis
sudo systemctl status redis --no-pager | head -12
```

## 四、验证（四项都要过）

```bash
# 1) 认证可用（用环境变量传密码，避免 -a 明文告警和 history 泄漏）
export REDISCLI_AUTH="$REDIS_PASS"
redis-cli ping                      # 期望 PONG

# 2) 无密码必须被拒绝
unset REDISCLI_AUTH; redis-cli ping # 期望 NOAUTH Authentication required

# 3) 内存上限与淘汰策略生效
export REDISCLI_AUTH="$REDIS_PASS"
redis-cli config get maxmemory      # 期望 268435456
redis-cli config get maxmemory-policy   # 期望 volatile-lru

# 4) 持久化确已关闭
redis-cli config get save           # 期望空字符串
redis-cli config get appendonly     # 期望 no
```

## 五、安全检查（必做，否则等于开门挖矿）

```bash
# 只允许 127.0.0.1 监听，不能出现 0.0.0.0:6379
sudo ss -ltnp | grep 6379

# 防火墙不要放行 6379
sudo firewall-cmd --list-ports 2>/dev/null || echo "firewalld 未启用"
```

再从**本地 Mac** 验证外网确实打不通（期望超时或 refused，绝不能连上）：

```bash
nc -zv -w 5 175.178.229.209 6379
```

同时确认腾讯云 Lighthouse 防火墙（安全组）**没有**放行 6379。

## 六、把密码交给应用

追加到 `/etc/shihai/shihai.env`（该文件已被 `shihai.service` 以 `EnvironmentFile` 加载）：

```bash
sudo bash -c "cat >> /etc/shihai/shihai.env" <<EOF
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=${REDIS_PASS}
EOF

sudo chmod 600 /etc/shihai/shihai.env
sudo grep -c REDIS_PASSWORD /etc/shihai/shihai.env    # 期望 1
```

改完 env 后需要重启应用才生效（发版时会自动重启，也可手动）：

```bash
sudo systemctl restart shihai
```

## 七、应用侧接入约定（代码改造后生效）

`application.properties` 将读取以下变量，缺省值保证**本地开发无 Redis 也能启动**：

```properties
spring.data.redis.host=${REDIS_HOST:127.0.0.1}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.timeout=3000ms
spring.data.redis.lettuce.pool.max-active=16
spring.data.redis.lettuce.pool.max-idle=8
spring.data.redis.lettuce.pool.min-idle=2

# 流式缓冲后端：redis | memory。redis 不可用时自动降级为 memory
app.ai.stream.buffer-backend=${STREAM_BUFFER_BACKEND:redis}
app.ai.stream.buffer-ttl-seconds=600
```

Key 命名与结构（`app.ai.stream.*`）：

| Key | 类型 | 用途 | TTL |
| --- | --- | --- | --- |
| `shihai:stream:{streamId}:meta` | Hash | 会话状态（RUNNING/DONE/FAILED）、lastSeq、创建时间 | 600s |
| `shihai:stream:{streamId}:chunks` | ZSet（score=seq） | 有序 chunk，续传时按 `seq > lastEventId` 取增量 | 600s |
| `shihai:stream:{streamId}:events` | Pub/Sub 频道 | 通知订阅端有新 chunk（支撑多实例） | — |

## 八、常见故障与排查

| 症状 | 原因 | 处理 |
| --- | --- | --- |
| 应用日志 `NOAUTH Authentication required` | `shihai.env` 里密码没写或没重启服务 | 检查 env，`systemctl restart shihai` |
| 应用日志 `Unable to connect to 127.0.0.1:6379` | Redis 未启动 | `systemctl status redis`；确认 `enable --now` 执行过 |
| `OOM command not allowed when used memory > maxmemory` | 缓冲堆积超过 256MB | 确认 TTL 生效、`maxmemory-policy` 是 `volatile-lru`；必要时上调 maxmemory |
| 外网能连上 6379 | `bind` 没生效或安全组放行了 | 立即改回 `bind 127.0.0.1 -::1`、关闭安全组端口、改密码 |
| 内存告警 | Redis 与 JVM 争抢 | 下调 `maxmemory` 到 128mb，或下调 JVM `-Xmx` |

## 九、回滚

Redis 是**增强项**，出问题可以随时摘掉，应用会退回进程内缓冲（功能不丢，只是多实例下不共享）：

```bash
# 应用侧降级（不用卸载 Redis）
sudo sed -i 's/^STREAM_BUFFER_BACKEND=.*/STREAM_BUFFER_BACKEND=memory/' /etc/shihai/shihai.env
grep -q STREAM_BUFFER_BACKEND /etc/shihai/shihai.env || echo "STREAM_BUFFER_BACKEND=memory" | sudo tee -a /etc/shihai/shihai.env
sudo systemctl restart shihai

# 彻底卸载 Redis
sudo systemctl disable --now redis
sudo dnf remove -y redis
```

---

## 附：一键脚本

上述步骤的等价脚本见 `deploy/install-redis.sh`，在服务器上以 root 执行：

```bash
sudo bash install-redis.sh
```

脚本会自动生成密码、写配置、启动、验证，并把密码追加到 `/etc/shihai/shihai.env`；已安装则跳过安装步骤（幂等）。
