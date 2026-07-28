#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

JUDGE_ENABLED="false"
CASE_IDS=""

for arg in "$@"; do
  case "$arg" in
    --judge)
      JUDGE_ENABLED="true"
      ;;
    --sample)
      CASE_IDS="rag-001,rag-015,rag-027"
      ;;
    *)
      echo "Unknown argument: $arg"
      echo "Usage: ./scripts/run-evals.sh [--judge] [--sample]"
      exit 1
      ;;
  esac
done

USER_MAVEN="/Users/95h/apache-maven-3.6.3/bin/mvn"
MAVEN_SETTINGS="/Users/95h/apache-maven-3.6.3/conf/mkc-settings.xml"

if [[ -x "./mvnw" ]]; then
  MAVEN_CMD=("./mvnw")
elif [[ -x "$USER_MAVEN" ]]; then
  MAVEN_CMD=("$USER_MAVEN")
elif command -v mvn >/dev/null 2>&1; then
  MAVEN_CMD=("mvn")
else
  echo "Maven was not found."
  echo "Expected Maven at: $USER_MAVEN"
  echo "On macOS, install Maven with: brew install maven"
  exit 1
fi

MAVEN_ARGS=()
if [[ -f "$MAVEN_SETTINGS" ]]; then
  MAVEN_ARGS+=(--settings "$MAVEN_SETTINGS")
fi

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "Missing OPENAI_API_KEY. Set the chat-model API key before starting an independent eval instance."
  exit 1
fi

if [[ -z "${OPENAI_EMBEDDING_API_KEY:-}" ]]; then
  echo "Missing OPENAI_EMBEDDING_API_KEY. Set the embedding-model API key before starting an independent eval instance."
  exit 1
fi

SPRING_ARGS="--app.mode=eval"
SPRING_ARGS="$SPRING_ARGS --workbench.eval.judge.enabled=$JUDGE_ENABLED"

if [[ -n "$CASE_IDS" ]]; then
  SPRING_ARGS="$SPRING_ARGS --workbench.eval.case-ids=$CASE_IDS"
fi

if curl --silent --fail --max-time 3 http://localhost:8080/api/health >/dev/null; then
  if [[ -n "$CASE_IDS" || "$JUDGE_ENABLED" == "true" ]]; then
    echo "--sample 和 --judge 需要通过独立实例注入评测参数。请先停止 8080 上的应用后再运行脚本。"
    exit 1
  fi

  echo "检测到应用已运行在 http://localhost:8080，直接调用评测接口。"
  curl --silent --show-error --fail --max-time 1800 \
    -X POST http://localhost:8080/api/eval/run
  echo
  exit 0
fi

"${MAVEN_CMD[@]}" "${MAVEN_ARGS[@]}" spring-boot:run -Dspring-boot.run.arguments="$SPRING_ARGS"
