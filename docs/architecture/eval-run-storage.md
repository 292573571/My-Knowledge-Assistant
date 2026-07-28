# 评测运行数据存储

评测题库继续使用 PostgreSQL 的 `eval_cases` 表。每次标准或增强检索评测完成后，运行汇总和每题完整结果都会写入 PostgreSQL，不再生成新的 `eval/results/*.json` 或 `eval/reports/*.md` 文件。

## 表结构

- `eval_runs`：运行 ID、用户归属、标准/增强模式、Judge 开关、时间和汇总质量指标。
- `eval_run_results`：单题评测的完整 JSON 快照，包含回答、来源、关键词命中、评分和检索调试信息。
- `rag_quality_audits`：流式 RAG 回答完成后的异步质量审计，取代 `data/rag-quality-audits.jsonl`。

运行结果会固化题目快照，后续编辑或删除题库用例不会改变历史评测结论。所有 Web 运行记录都按当前用户隔离。

## API

```text
POST /api/eval/run
GET  /api/eval/runs
GET  /api/eval/runs/{runId}
```

`POST /api/eval/run` 返回值会在写库后从数据库重新读取，保证页面使用的数据与持久化历史一致。`GET /api/eval/runs` 返回当前用户的运行汇总，`GET /api/eval/runs/{runId}` 返回某次运行的完整单题结果。
