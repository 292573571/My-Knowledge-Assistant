#!/usr/bin/env bash
#
# 在 OpenCloudOS 9 上安装并加固 Redis，供 shihai 流式缓冲使用。
# 用法（服务器上以 root 执行）：
#   sudo bash install-redis.sh
#
# 特性：
#   - 幂等：已安装/已配置则跳过，可重复执行
#   - 只监听 127.0.0.1，强制密码，内存硬上限 256MB，关闭持久化
#   - 自动把 REDIS_* 写入 /etc/shihai/shihai.env（不重复写）
#
set -euo pipefail

REDIS_CONF="/etc/redis/redis.conf"
ENV_FILE="/etc/shihai/shihai.env"
MARKER="# ===== shihai stream buffer config ====="
MAXMEMORY="${MAXMEMORY:-256mb}"

log() { printf '\033[1;34m[redis-setup]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行：sudo bash $0"

# ---------- 1. 安装 ----------
if rpm -q redis >/dev/null 2>&1; then
  log "redis 已安装：$(rpm -q redis)，跳过安装"
else
  log "安装 redis..."
  dnf install -y redis
  log "已安装：$(rpm -q redis)"
fi

[[ -f "$REDIS_CONF" ]] || die "找不到配置文件 $REDIS_CONF"

# ---------- 2. 配置 ----------
if grep -qF "$MARKER" "$REDIS_CONF"; then
  log "检测到已有 shihai 配置段，跳过写配置"
  REDIS_PASS="$(awk '/^requirepass /{print $2}' "$REDIS_CONF" | tail -1)"
  [[ -n "$REDIS_PASS" ]] || die "配置段存在但未找到 requirepass，请检查 $REDIS_CONF"
else
  BACKUP="${REDIS_CONF}.bak.$(date +%Y%m%d%H%M%S)"
  cp "$REDIS_CONF" "$BACKUP"
  log "已备份原配置到 $BACKUP"

  REDIS_PASS="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"
  [[ ${#REDIS_PASS} -eq 32 ]] || die "密码生成失败"

  cat >> "$REDIS_CONF" <<EOF

$MARKER
# 只监听本机回环，绝不暴露公网
bind 127.0.0.1 -::1
protected-mode yes
port 6379

# 认证
requirepass $REDIS_PASS

# 内存硬上限，与 JVM / Chroma / PostgreSQL 共存
maxmemory $MAXMEMORY
maxmemory-policy volatile-lru

# 流式缓冲是易失数据，关闭持久化：省 IO、省内存、无需备份
save ""
appendonly no

# 长连接保活，配合 SSE 心跳
tcp-keepalive 300
timeout 0
EOF
  log "配置已写入（maxmemory=$MAXMEMORY，仅监听 127.0.0.1，已启用密码）"
fi

# ---------- 3. 启动 ----------
log "启动并设置开机自启..."
systemctl enable redis >/dev/null 2>&1 || true
systemctl restart redis
sleep 2
systemctl is-active --quiet redis || die "redis 启动失败，看 journalctl -u redis -n 50"
log "redis 服务状态：$(systemctl is-active redis)"

# ---------- 4. 验证 ----------
log "开始验证..."
export REDISCLI_AUTH="$REDIS_PASS"

PONG="$(redis-cli ping 2>&1 || true)"
[[ "$PONG" == "PONG" ]] || die "认证连接失败：$PONG"
log "  [ok] 认证连接正常（PING -> PONG）"

NOAUTH="$(env -u REDISCLI_AUTH redis-cli ping 2>&1 || true)"
if [[ "$NOAUTH" == *NOAUTH* ]]; then
  log "  [ok] 匿名访问已被拒绝"
else
  die "危险：未认证也能访问 Redis（返回 $NOAUTH），请检查 requirepass"
fi

MM="$(redis-cli config get maxmemory | tail -1)"
MP="$(redis-cli config get maxmemory-policy | tail -1)"
AOF="$(redis-cli config get appendonly | tail -1)"
log "  [ok] maxmemory=$MM  policy=$MP  appendonly=$AOF"

if ss -ltn | grep -q '0\.0\.0\.0:6379\|\[::\]:6379'; then
  die "危险：Redis 正在监听所有网卡，请确认 bind 配置生效"
fi
log "  [ok] 仅监听回环地址："
ss -ltn | grep 6379 | sed 's/^/        /'

# ---------- 5. 写入应用环境变量 ----------
if [[ -f "$ENV_FILE" ]]; then
  if grep -q '^REDIS_PASSWORD=' "$ENV_FILE"; then
    warn "$ENV_FILE 已存在 REDIS_PASSWORD，未覆盖。如需更新请手动编辑。"
  else
    cat >> "$ENV_FILE" <<EOF
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=$REDIS_PASS
EOF
    chmod 600 "$ENV_FILE"
    log "已把 REDIS_HOST/PORT/PASSWORD 追加到 $ENV_FILE"
    warn "需要重启应用生效：systemctl restart shihai"
  fi
else
  warn "未找到 $ENV_FILE，请手动把下面三行写入应用环境变量文件："
  echo "        REDIS_HOST=127.0.0.1"
  echo "        REDIS_PORT=6379"
  echo "        REDIS_PASSWORD=$REDIS_PASS"
fi

echo
log "完成。Redis 密码（请妥善保存）：$REDIS_PASS"
log "外网连通性请从本地机器复验应为失败：nc -zv -w 5 <公网IP> 6379"
