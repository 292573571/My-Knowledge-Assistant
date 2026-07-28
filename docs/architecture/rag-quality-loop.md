# RAG 质量闭环

## 评测集

评测题库位于 `eval/questions.jsonl`。每行一个 JSON 对象，包含：

- `question`：真实用户问题。
- `expectedSources`、`expectedHeadingPaths`：预期资料和章节。
- `expectedKeywords`、`forbiddenKeywords`：关键答案点与禁止结论。
- `requireLocalEvidence`：答案是否必须有本地资料依据。
- `allowModelFallback`：知识库未命中时是否允许通用模型补充。
- `expectNoAnswer`：是否应明确拒答或说明资料不足。

当前题库有 30 条项目架构问题。新增真实资料后，应将其中至少一半替换为你高频会问的问题，并标记正确来源和关键点。

## 运行与对比

运行全部评测：

```text
./scripts/run-evals.sh
```

使用 LLM Judge：

```text
./scripts/run-evals.sh --judge
```

每次运行都会将运行汇总和每题完整检索结果写入 PostgreSQL。使用 `GET /api/eval/runs` 查看当前用户的历史运行，使用 `GET /api/eval/runs/{runId}` 读取完整结果；不再生成 `eval/results` 和 `eval/reports` 文件。

重点查看：检索命中率、引用正确率、关键点覆盖率、无依据回答率、模型补充率和拒答正确率。

## 参数实验

每次只调整一项参数，并运行同一套评测集。例如：

```text
--workbench.rag.top-k=8
--workbench.rag.similarity-threshold=0.40
--workbench.rag.query-rewrite.enabled=true
--workbench.rag.multi-query.enabled=true
```

查询改写与多查询默认关闭，因为它们会增加模型调用并影响首 token 延迟。只有当评测报告显示检索命中率或引用正确率有稳定收益时，才应在生产环境开启。

## 检索调试

前端主导航的“检索调试”页面调用 `POST /api/rag/debug`，展示：

```text
原始问题
→ 实际检索查询（包含改写或多查询）
→ 候选分块、分数、标题路径和预览
→ 最终是否进入上下文
```

接口按当前登录用户范围过滤候选，仅用于调试，不会调用回答模型或写入会话。

## 在线质量审计

流式回答完成并保存后，`RagQualityAuditService` 异步将质量信号写入 PostgreSQL 的 `rag_quality_audits` 表。

该操作不阻塞首 token 或用户看到的回答。

- 无本地来源：记录为 `MODEL_SUPPLEMENT`。
- 有本地来源且质量闸门关闭：记录为 `BASIC_PASS`，不代表 LLM 已验证依据。
- 开启 `workbench.rag.quality-gate.enabled=true`：后台额外校验回答是否被引用片段支持，记录为 `PASS` 或 `FAIL`。
