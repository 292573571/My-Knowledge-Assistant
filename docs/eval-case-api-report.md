# Eval Case 数据库化报告

## 设计

- `eval_cases` 使用 JPA 持久化，`owner_user_id` 关联 `app_users`；所有读取、更新、删除和运行选择均按当前认证用户的数据库 ID 过滤。
- 用户首次 `GET /api/eval/cases` 或运行评测时，从 `eval/questions.jsonl` 导入前 30 条模板。命令行 `app.mode=eval` 仍直接读取 JSONL。
- 列表字段以 JSON 字符串存储，并在 `EvalCaseService` 中转换为 `EvalCase` 和 API 响应对象。
- `POST /api/eval/run` 的 `enhanced=true` 通过不可变 `RagChatOptions` 仅传给本次评测调用，强制查询改写和多查询；不修改 `RagService` 的全局配置，因而不会影响并发普通聊天。

## API

- `GET /api/eval/cases`
- `POST /api/eval/cases`
- `PUT /api/eval/cases/{id}`
- `DELETE /api/eval/cases/{id}`
- `POST /api/eval/run`，可选请求体：`{"caseIds":[1,2],"enhanced":true}`。未提供或空 `caseIds` 时运行当前用户全部用例。

请求 DTO 使用 Jackson `@JsonAlias` 同时接受驼峰和下划线字段，例如 `caseIds/case_ids`、`expectNoAnswer/expect_no_answer`。API 输出为驼峰字段。

## 测试

- `EvalCaseServiceTest` 覆盖所有权范围拒绝、列表 JSON 转换和所有权范围更新。
- 完成时执行 `mvn test`，结果见本次任务最终报告。
