# 发版评测门禁（Eval Deploy Gate）

发版脚本在**健康检查通过之后**调用 `/api/eval/gate` 跑一次基线评测，指标未达标就 `exit 1`，
触发脚本自带的回滚逻辑把上一版拉回来。目的是拦住「功能没坏但检索/回答变差」的退化版本。

## 开关与配置

全部写在 `/etc/shihai/shihai.env`（`shihai.service` 的 EnvironmentFile），改完 `systemctl restart shihai`。

| 变量 | 作用 | 默认 |
|---|---|---|
| `EVAL_DEPLOY_GATE` | `0` 关闭 / `warn` 只告警 / `1` 强制阻断 | `0` |
| `EVAL_GATE_TOKEN` | 运维令牌，部署脚本带 `X-Ops-Token` 调用 | 空（空=入口不可用） |
| `EVAL_GATE_SUITE` | 跑哪个题库集合 | `REGRESSION` |
| `EVAL_GATE_OWNER_ACCOUNT` | 题库归属账号，空则取最早的超管 | 空 |
| `EVAL_GATE_TIMEOUT_SECONDS` | 单次门禁超时（秒） | `900` |

后端侧对应属性（`application.properties` 已接好）：
`workbench.eval.gate.enabled` / `ops-token` / `owner-account` / `suite`，
阈值沿用 `workbench.eval.gate.min-pass-rate`（默认 0.8）、`min-recall-at-5`（0.8）、
`min-mrr`（0.7）、`max-unsupported-answer-rate`（0.05）。

> 注意：`workbench.eval.gate.enabled` 必须为 `true` 才能调用，否则返回 503。
> 部署脚本已通过 `EVAL_DEPLOY_GATE` 同时控制「是否调用」，两个开关都要开。

## 开启步骤

```bash
# 1) 生成令牌（32 字节随机）
TOKEN=$(openssl rand -hex 32)
echo "生成令牌: $TOKEN"

# 2) 写入环境文件（幂等：先删旧行再追加）
sudo sed -i '/^EVAL_GATE_TOKEN=/d;/^EVAL_DEPLOY_GATE=/d;/^EVAL_GATE_SUITE=/d;/^WORKBENCH_EVAL_GATE_ENABLED=/d' /etc/shihai/shihai.env
sudo tee -a /etc/shihai/shihai.env >/dev/null <<EOF
WORKBENCH_EVAL_GATE_ENABLED=true
EVAL_GATE_TOKEN=$TOKEN
EVAL_GATE_SUITE=REGRESSION
EVAL_DEPLOY_GATE=warn
EOF

# 3) 重启生效
sudo systemctl restart shihai
sleep 20 && curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/health
```

先跑 **`warn`**：门禁照跑、结果写进发版输出，但不阻断。观察 2–3 次发版的通过率稳定后，再改 `EVAL_DEPLOY_GATE=1` 强制。

## 手工试跑门禁（不部署）

```bash
TOKEN=$(sudo grep '^EVAL_GATE_TOKEN=' /etc/shihai/shihai.env | cut -d= -f2- | tr -d '"')
curl -s -X POST "http://127.0.0.1:8080/api/eval/gate?suite=REGRESSION" \
  -H "X-Ops-Token: $TOKEN" | python3 -m json.tool
```

返回示例：

```json
{"passed": true, "suite": "REGRESSION", "owner": "admin", "total": 36, "passedCases": 33,
 "passRate": 0.916, "recallAt5": 0.94, "mrr": 0.88, "unsupportedAnswerRate": 0.0,
 "runId": "20261103-115000-123", "failures": []}
```

判定规则：

- `passed=true` → HTTP 200，放行。
- 指标不达标 → HTTP 200 + `passed=false` + `failures`（部署脚本 grep `"passed":true` 判断，不依赖状态码）。
- `EVAL_GATE_TOKEN` 没配 → 401；`workbench.eval.gate.enabled=false` → 503；同一账号有评测在跑 → 409；该集合没有题 → `passed=false`（**空题库不放行**）。

## 题库准备

门禁默认跑 `REGRESSION`。超管账号下若没有任何题，服务会自动播种内置 36 条基础题；
真实语料题通过「系统维护 → 检索诊断 → 评测题库 → 生成草稿题」生成，人工审核后把 `suite` 改成 `REGRESSION` 即为基线。

## 排查

| 现象 | 原因 |
|---|---|
| 发版输出「跳过评测门禁」 | `EVAL_DEPLOY_GATE` 未设置或为 `0` |
| 「缺少 EVAL_GATE_TOKEN」 | 令牌没写进 env 文件，或写入后没重启 |
| 「评测门禁调用失败」 | 门禁超时（调大 `EVAL_GATE_TIMEOUT_SECONDS`）或后端 503（`WORKBENCH_EVAL_GATE_ENABLED` 没开） |
| 门禁不通过导致回滚 | 看发版输出里的 `failures`，先在评测页定位是哪几条题退化 |

## 安全

`/api/eval/gate` 在 `AuthFilter` 白名单里，靠 `X-Ops-Token` 鉴权（常量时间比较）。
令牌泄露等于可触发评测（消耗模型额度），因此：

- 令牌用 32 字节随机值，不要复用登录密码；
- 如需进一步收紧，可在 nginx 对该路径加 `deny all;`，部署脚本走 `127.0.0.1:8080` 不受影响。
