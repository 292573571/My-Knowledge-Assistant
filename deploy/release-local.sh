#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DEPLOY_HOST=${DEPLOY_HOST:-175.178.229.209}
DEPLOY_PORT=${DEPLOY_PORT:-22}
DEPLOY_USER=${DEPLOY_USER:-deploy}
DEPLOY_KEY=${DEPLOY_KEY:-$HOME/.ssh/zhihai_deploy_key}
PUBLIC_HEALTH_URL=${PUBLIC_HEALTH_URL:-http://$DEPLOY_HOST/api/health}
REMOTE_DEPLOY_SCRIPT=/usr/local/sbin/deploy-zhihai
assume_yes=false

usage() {
  cat <<'EOF'
用法: ./deploy/release-local.sh [--yes]

环境变量：
  DEPLOY_HOST        服务器地址，默认 175.178.229.209
  DEPLOY_PORT        SSH 端口，默认 22
  DEPLOY_USER        SSH 用户，默认 deploy
  DEPLOY_KEY         SSH 私钥，默认 ~/.ssh/zhihai_deploy_key
  MAVEN_BIN          Maven 命令路径
  PUBLIC_HEALTH_URL  公网健康检查地址
EOF
}

for argument in "$@"; do
  case "$argument" in
    --yes) assume_yes=true ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知参数: $argument" >&2; usage >&2; exit 2 ;;
  esac
done

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "缺少命令: $1" >&2; exit 1; }
}

require_command git
require_command rsync
require_command ssh
require_command curl
require_command tar

if [[ -n "${MAVEN_BIN:-}" ]]; then
  maven_bin=$MAVEN_BIN
elif command -v mvn >/dev/null 2>&1; then
  maven_bin=$(command -v mvn)
elif [[ -x "$HOME/apache-maven-3.6.3/bin/mvn" ]]; then
  maven_bin="$HOME/apache-maven-3.6.3/bin/mvn"
else
  echo "未找到 Maven，请通过 MAVEN_BIN 指定 mvn 路径" >&2
  exit 1
fi

[[ -x "$maven_bin" ]] || { echo "Maven 不可执行: $maven_bin" >&2; exit 1; }
[[ -f "$DEPLOY_KEY" ]] || { echo "部署私钥不存在: $DEPLOY_KEY" >&2; exit 1; }

ssh_options=(
  -i "$DEPLOY_KEY"
  -p "$DEPLOY_PORT"
  -o IdentitiesOnly=yes
  -o BatchMode=yes
  -o ConnectTimeout=15
  -o ServerAliveInterval=30
  -o ServerAliveCountMax=6
)
remote="$DEPLOY_USER@$DEPLOY_HOST"

cd "$PROJECT_ROOT"
branch=$(git branch --show-current)
[[ "$branch" == "master" ]] || { echo "只能从 master 发版，当前分支: $branch" >&2; exit 1; }

if ! git diff --quiet || ! git diff --cached --quiet || [[ -n "$(git ls-files --others --exclude-standard)" ]]; then
  echo "工作区存在未提交内容，请先提交或清理后再发版" >&2
  git status --short
  exit 1
fi

echo "正在确认本地 master 已推送到 origin/master..."
git fetch --quiet origin master
revision=$(git rev-parse HEAD)
remote_revision=$(git rev-parse origin/master)
[[ "$revision" == "$remote_revision" ]] || {
  echo "本地 master 与 origin/master 不一致，请先完成 pull/push" >&2
  exit 1
}

if [[ "$assume_yes" != true ]]; then
  printf '确认发布 master %s 到 %s？[y/N] ' "${revision:0:12}" "$DEPLOY_HOST"
  read -r confirmation
  [[ "$confirmation" == "y" || "$confirmation" == "Y" ]] || { echo "已取消"; exit 0; }
fi

echo "正在检查服务器发布环境..."
ssh "${ssh_options[@]}" "$remote" \
  "command -v rsync >/dev/null && test -x '$REMOTE_DEPLOY_SCRIPT' && sudo -n '$REMOTE_DEPLOY_SCRIPT' >/dev/null 2>&1; test \$? -eq 2" || {
  echo "服务器预检失败，请检查 rsync、$REMOTE_DEPLOY_SCRIPT 和 deploy 用户的 sudo 配置" >&2
  exit 1
}

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/zhihai-release.XXXXXX")
archive="$work_dir/zhihai-$revision.tar.gz"
cleanup() {
  rm -rf "$work_dir"
}
trap cleanup EXIT

echo "[1/5] 测试并打包后端"
"$maven_bin" --batch-mode --no-transfer-progress clean package

backend_jars=(target/*.jar)
if [[ ${#backend_jars[@]} -ne 1 || ! -f "${backend_jars[0]}" ]]; then
  echo "预期 target 中存在一个可执行 JAR" >&2
  exit 1
fi

echo "[2/5] 安装依赖并构建前端"
(
  cd frontend
  if [[ -f package-lock.json ]]; then
    require_command npm
    npm ci
  elif [[ -f yarn.lock ]]; then
    require_command yarn
    yarn install --frozen-lockfile
  else
    echo "frontend 中缺少 package-lock.json 或 yarn.lock" >&2
    exit 1
  fi
  npm run build
)

echo "[3/5] 生成发布包"
mkdir -p "$work_dir/release/backend" "$work_dir/release/frontend"
cp "${backend_jars[0]}" "$work_dir/release/backend/zhihai.jar"
cp -R frontend/dist/. "$work_dir/release/frontend/"
printf '%s\n' "$revision" > "$work_dir/release/REVISION"
COPYFILE_DISABLE=1 tar -czf "$archive" -C "$work_dir/release" .
ls -lh "$archive"

remote_archive="/tmp/zhihai-$revision.tar.gz"

echo "[4/5] 上传发布包"
rsync \
  --archive \
  --compress \
  --partial \
  --progress \
  -e "ssh -i $DEPLOY_KEY -p $DEPLOY_PORT -o IdentitiesOnly=yes -o BatchMode=yes -o ServerAliveInterval=30" \
  "$archive" \
  "$remote:$remote_archive"

echo "[5/5] 部署并执行健康检查"
ssh "${ssh_options[@]}" "$remote" \
  "sudo -n '$REMOTE_DEPLOY_SCRIPT' '$remote_archive' '$revision'"

public_health_attempts=${PUBLIC_HEALTH_ATTEMPTS:-30}
public_health_interval=${PUBLIC_HEALTH_INTERVAL_SECONDS:-2}
for attempt in $(seq 1 "$public_health_attempts"); do
  if curl --fail --silent --show-error --max-time 15 "$PUBLIC_HEALTH_URL" >/dev/null; then
    echo "发版成功"
    echo "版本: $revision"
    echo "健康检查: $PUBLIC_HEALTH_URL"
    exit 0
  fi
  sleep "$public_health_interval"
done

echo "远端部署命令已完成，但公网健康检查超时: $PUBLIC_HEALTH_URL" >&2
echo "请检查反向代理、防火墙和公网 DNS；远端本地健康检查已由 $REMOTE_DEPLOY_SCRIPT 执行" >&2
exit 1
