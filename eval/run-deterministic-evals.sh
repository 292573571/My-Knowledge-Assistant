#!/usr/bin/env bash
set -euo pipefail

# 提交级四层确定性回归，不依赖 PostgreSQL、Chroma 或模型密钥。
MVN_BIN="${MAVEN_BIN:-mvn}"
"$MVN_BIN" --batch-mode --no-transfer-progress \
  -Dtest='RuleBasedEvaluatorTest,ParserRuleBasedEvaluatorTest,ContextRuleBasedEvaluatorTest,EvalRetrievalMetricsTest,EvalQualityGateTest,EvalDimensionSummarizerTest' \
  test
