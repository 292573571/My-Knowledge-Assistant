#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$project_root"

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

"$maven_bin" --batch-mode --no-transfer-progress test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/test-classpath.txt
java -cp "target/test-classes:$(<target/test-classpath.txt)" \
  com.example.workbench.eval.MultiformatEvalFixtureGenerator

echo "多格式评测样本已生成到 eval/multiformat/fixtures"
