# Spring Boot 4 / Spring AI 2 升级预研

> 评估日期：2026-11-10　基线 commit：`f1b0c2f`
> 本文的所有版本号、EOL 日期、artifact 存在性、编译与测试结果**均为当日实测**，非记忆推断。
> 试验在 `/tmp/spike-boot4` 副本上进行，**仓库代码未改动**。

---

## 0. 结论（与预期不同，请注意）

| 方案 | 结果 | 证据 |
|---|---|---|
| 先升 Boot 4.1.2、Spring AI 保持 1.1.8 | ❌ **不可行** | 5 个用例报 `NoSuchMethodError: HttpHeaders.addAll(MultiValueMap)` |
| 一步升 Boot 4.1.2 + Spring AI 2.0.2 | ✅ **可行** | 662 用例中 658 通过，仅剩 4 个用例待修 |
| 只升到 Boot 3.5.16 过渡 | ❌ **已无意义** | 3.5 线已于 2026-06-30 停止免费维护 |

**推荐：一次性升到 Spring Boot 4.1.2 + Spring AI 2.0.2。**

原计划的「两阶段迁移」（先升框架、再升 AI）走不通，因为 **Spring AI 1.1.x 与 Spring Framework 7 二进制不兼容**：

```
java.lang.NoSuchMethodError:
  'void org.springframework.http.HttpHeaders.addAll(org.springframework.util.MultiValueMap)'
    at org.springframework.ai.openai.api.OpenAiApi.lambda$new$0(OpenAiApi.java:149)
    at org.springframework.ai.openai.OpenAiApi.<init>(OpenAiApi.java:153)
    at ...ModelClientFactory.build(ModelClientFactory.java:69)
```

`HttpHeaders.addAll(MultiValueMap)` 在 Framework 6.2 存在、在 7.0 已被删除。Spring AI 1.1.8 编译期依赖 Boot **3.5.15**，运行期无法加载到 Framework 7 上。
这不是配置问题，没有兼容层可绕。

---

## 1. 支持窗口（实测）

| 版本线 | 发布 | 免费支持截止 | 现状（2026-11-10） |
|---|---|---|---|
| Boot 3.4 | 2024-11 | **2025-12-31** | ❌ 停更 11 个月，线内最新 3.4.13（我们在 3.4.5，落后 8 个补丁） |
| Boot 3.5 | 2025-05 | **2026-06-30** | ❌ 已停更，最后补丁 3.5.16 |
| Boot 4.0 | 2025-11 | 2026-12-31 | ⚠️ 只剩约 7 周 |
| **Boot 4.1** | 2026-06 | **2027-07-31** | ✅ 唯一合理目标，最新 4.1.2 |

---

## 2. 依赖扫描结果

`mvn versions:display-dependency-updates`（联网）+ Maven Central metadata 交叉核对：

| 组件 | 当前 | 最新 GA | Boot 4.1.2 托管值 | 建议 |
|---|---|---|---|---|
| Spring Boot | 3.4.5 | 4.1.2（4.2 仍 RC2） | — | **升 4.1.2** |
| Spring Framework | 6.2.6 | — | 7.0.10 | 随 Boot |
| Spring Security | 6.4.5 | 7.1.2 | 7.1.2 | 随 Boot |
| Hibernate ORM | 6.6.13 | — | 7.4.13 | 随 Boot |
| Flyway | 10.20.1 | 13.12.1 | **12.4.0** | 随 Boot，**不要跳 13** |
| Jackson | 2.18.3 | 3.1.7 | 3.1.7（另带 jackson-2-bom 2.21.7） | 随 Boot，见 §6 |
| JUnit Jupiter | 5.11.4 | 6.0.3 | 6.0.3 | 随 Boot（实测零源码改动） |
| Tomcat | 10.1.40 | — | 11.0.27 | 随 Boot（Servlet 6.1） |
| Log4j2 | 2.24.3 | — | 2.25.5 | 随 Boot |
| Spring AI | 1.1.8 | 2.0.2（2.1 仍 RC2） | 不托管 | **升 2.0.2** |
| POI | 5.4.1 | 6.0.0 | 不托管 | **不跟**，5.5.1 单独评估 |
| PDFBox | 3.0.5 | 3.0.8 | 不托管 | 可顺手升 |
| jsoup | 1.23.1 | 1.24.1 | 不托管 | 可顺手升 |
| commons-pool2 | 2.12.1 | 2.13.1 | 不托管 | 可顺手升 |
| PostgreSQL JDBC | 42.7.5 | 42.7.14 | 42.7.14 | 随 Boot |

目标坐标存在性（HTTP 200 实测）：

```
spring-boot-starter-{webmvc,flyway,classic,restclient,data-jpa-test,test,
                     actuator,mail,data-redis,log4j2,validation,data-jpa,web} @ 4.1.2 → 全 200
spring-ai-starter-{model-openai,vector-store-chroma,mcp-server-webmvc} @ 2.0.2       → 全 200
poi-ooxml 5.5.1 / pdfbox 3.0.8 / jsoup 1.24.1 / commons-pool2 2.13.1                → 全 200
```

---

## 3. 本仓库规模

| 指标 | 数值 |
|---|---|
| 主代码 | 437 文件 / 37,748 行 |
| 测试 | 144 文件 / 662 个可执行用例 |
| `import jakarta.persistence` | 276 处 |
| Hibernate 专有注解 | **仅 `@Comment`（38 文件）**，无 `@Type`/`@TypeDef` |
| `@MockBean` / `@SpyBean` | **0** |
| 自定义自动配置 / `ImportsFile` | 0 |
| 自定义 `WebMvcConfigurer` / `FilterRegistrationBean` | 0（全是 `@Component` 过滤器） |

结论：Boot 4 的「模块拆分 + starter 重命名」对业务代码几乎零影响，成本集中在少数几个精确的 API 点上。

---

## 4. 实测改动清单（13 个文件）

以下是在副本上**实际改完并跑通**的清单，不是推测：

### 4.1 pom.xml

```diff
-        <version>3.4.5</version>
+        <version>4.1.2</version>

-        <spring-ai.version>1.1.8</spring-ai.version>
+        <spring-ai.version>2.0.2</spring-ai.version>

-            <artifactId>spring-boot-starter-web</artifactId>
+            <artifactId>spring-boot-starter-webmvc</artifactId>

+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-flyway</artifactId>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-restclient</artifactId>
+        </dependency>
```

两个新增 starter 都是**静默失败型**陷阱：
- 没有 `starter-flyway`：应用照常启动，**Flyway 迁移静默不执行**；
- 没有 `starter-restclient`：`org.springframework.boot.http.client` 包不存在，编译期才会暴露。

### 4.2 主代码（6 个文件）

| 文件 | 改动 | 原因 |
|---|---|---|
| `config/ChromaHttpClientConfig` | `ClientHttpRequestFactorySettings` → `HttpClientSettings`；`ObjectMapper` → `JsonMapper`；`.objectMapper()` → `.jsonMapper()` | Boot 4 重命名；Spring AI 2.0 Chroma 改用 Jackson 3 |
| `config/SpringAiHttpTimeoutConfig` | 同上 + `org.springframework.boot.web.client.RestClientCustomizer` → `org.springframework.boot.restclient.RestClientCustomizer` | 模块拆分 |
| `observability/ChromaHealthIndicator` | `org.springframework.boot.actuate.health.*` → `org.springframework.boot.health.contributor.*` | 健康检查 SPI 独立成 `spring-boot-health` 模块 |
| `logview/LogController` | `Specification.where(null)` → `Specification.unrestricted()` | Spring Data JPA 4 新增 `where(PredicateSpecification)` 重载，传 `null` 产生**歧义** |
| `modelconfig/ModelClientFactory` | 删除 `OpenAiApi`，改用 `OpenAiChatOptions.builder().baseUrl().apiKey().timeout()` + `OpenAiChatModel.builder().options()` | **Spring AI 2.0 移除 `OpenAiApi`**，改用官方 `com.openai:openai-java` SDK |
| `rag/LocalChatClient` | `chatOptions()` 返回 Builder 而非构建好的实例 | 2.0 的 `.options()` 只接受 Builder |

### 4.3 配置（1 个文件）

```diff
-spring.ai.openai.embedding.options.model=BAAI/bge-m3
+spring.ai.openai.embedding.model=BAAI/bge-m3
```

非 chat 模型的 `.options.` 中缀在 2.0 被移除（chat 的 `.options.` 保留）。

### 4.4 测试（5 个文件）

| 文件 | 改动 |
|---|---|
| `SpringAiHttpTimeoutConfigTest` | `RestClientCustomizer` 包名 |
| `ChromaHealthIndicatorTest` | `Health` / `Status` 包名 |
| `GlobalExceptionHandlerNotFoundTest` | `NoResourceFoundException` 新增第 2 参：`(HttpMethod, String requestUri, String resourcePath)` |
| `ChromaHttpClientConfigTest` | `new ObjectMapper()` → `JsonMapper.builder().build()` |
| `SpringAiUpgradeContextTest` | 删除 `RetryTemplate` mock（2.0 不再需要）；`embedding.options.model` → `embedding.model` |

**JUnit 5 → 6：零源码改动。** 663 个 `@Test` 注解全部直接编译通过。

---

## 5. 试验结果（决定性证据）

### 5.1 试验 A：Boot 4.1.2 + Spring AI **1.1.8**

| 阶段 | 结果 |
|---|---|
| 主代码编译 | ✅（改 4 文件 + 4 处 pom） |
| 测试代码编译 | ✅（改 3 文件） |
| 全量测试 | ❌ **662 用例，0 失败，5 错误** |

5 个错误全部同一根因：

```
NoSuchMethodError: 'void HttpHeaders.addAll(MultiValueMap)'
  → OpenAiApi.lambda$new$0(OpenAiApi.java:149)
  → OpenAiApiChatAutoConfiguration.openAiApi(...)
  → SpringAiConfig.chatClient(...)
```

**结论：此路不通。** 原计划的两阶段迁移作废。

### 5.2 试验 B：Boot 4.1.2 + Spring AI **2.0.2**（推荐路径）

| 阶段 | 结果 |
|---|---|
| 主代码编译 | ✅ 437 文件 |
| 测试代码编译 | ✅ 144 文件 |
| 全量测试 | **662 用例，1 失败，3 错误 → 658 通过（99.4%）** |

过程中修掉的一个真实回归（值得单独记）：

> **`ModelClientFactory` 的 baseUrl 归一化必须重写。**
> 2.0 删除了 `completionsPath` 自定义，改由官方 SDK 在 baseUrl 后拼 `/chat/completions`。
> 历史配置 `baseUrl=https://api.siliconflow.cn` 不带 `/v1`，实测请求打到 `/chat/completions` → **404**。
> 修复：`if (!baseUrl.endsWith("/v1")) baseUrl += "/v1";`，修复后该测试 **2/2 绿**。

### 5.3 剩余 4 个用例（迁移时必须处理）

| 用例 | 现象 | 根因 |
|---|---|---|
| `ChromaHttpClientConfigTest.slowChromaFailsAtReadTimeoutInsteadOfHangingForever` | 期望 <1s 超时，实测 3.20s | `ClientHttpRequestFactorySettings` → `HttpClientSettings` 后，`detect()` 选到的工厂与读超时语义不同（**不报错、行为变**） |
| `SpringAiUpgradeContextTest` ×3 | `collection should pre-exist or have been initialized` | Spring AI 2.0 的 `ChromaVectorStore.Builder` 新增 `initializeImmediately(boolean)`，集合不再在构建时创建；`ChromaCollectionInitializer`（`BeanPostProcessor`）依赖的 `ChromaApi` 契约需同步适配 |

这 4 个都在**配置/初始化层**，不涉及业务逻辑，属于可预期的收尾工作。

---

## 6. 不报编译错、但会改行为的高危项

这一类比编译错误危险——**构建全绿，线上 JSON 或 SQL 变了**。

| 项 | 影响 | 我们的暴露面 |
|---|---|---|
| Jackson 3 默认**属性按字母序** + 日期 ISO-8601 | REST 响应字段顺序、时间格式变化 | 前端 Vue、MCP 客户端、评测脚本都在解析 JSON |
| Jackson 2 与 3 **共存** | 两套 ObjectMapper 语义不同 | 实测：Jackson 3（3.1.7）由 Boot/Flyway 引入，Jackson 2（2.21.7）由 `spring-ai-starter-model-openai` 2.0.2 带入。业务 26 个文件仍用 Jackson 2，**能编译能运行**，但 MVC 序列化已换成 Jackson 3 |
| `HttpClientSettings` 读超时语义 | Chroma 卡死时可能不再按预期断链 | 实测已复现 3.20s（见 §5.3），需重新标定 |
| Chroma 集合初始化时机 | `initializeImmediately` 默认 false，可能首查失败 | `ChromaCollectionInitializer` 必须适配 |
| Hibernate 6 → 7 | 生成 SQL / flush 顺序 / 批处理差异 | 276 处 JPA 实体，含财务对账类查询 |
| Flyway 10 → 12 | 校验和历史表行为、`baseline-version=0` 语义 | `spring.flyway.baseline-*` 2 项 |
| Spring AI 2.0 工具循环外移到 `ToolCallingAdvisor` | 工具调用次数、token 统计口径变化 | 刚做的 `teaching-*` 用量埋点 |
| MCP SDK 升 2.0，Streamable HTTP 成默认 | 客户端握手方式变化 | 线上 `spring.ai.mcp.server.protocol=STREAMABLE`，需真实客户端冒烟 |

---

## 7. 执行顺序（建议）

1. **先本地跑通**：按 §4 的 13 处改动实施，`mvn clean verify` 全绿（含 jacoco 阈值）
2. **修 §5.3 的 4 个用例**，重点是 Chroma 超时与集合初始化——这两处在生产是"静默失效"
3. **JSON 契约 diff**：升级前后抓同一批接口响应，比对字段集合与类型（允许顺序变化，但确认无消费方依赖）
4. **刷数据副本跑一次 Flyway migrate**，不要在线上直接试
5. **发版**：`deploy-shihai`，保留上一 release 可回滚
6. **上线后实测**：MCP `initialize` / `tools/list` / `tools/call`、SSE 流式、评测门禁

## 8. 验收清单（缺一不可）

- [ ] `mvn clean verify`：**662 用例全过** + jacoco 三包阈值（rag 0.60 / eval 0.50 / modelconfig 0.20）
- [ ] 前端 `yarn build` 通过，`dist` 产物 hash 与升级前对比
- [ ] 评测门禁：SMOKE 7/7、REGRESSION 4/4、FORMAT 7 例
- [ ] JSON 契约 diff 无破坏性变更
- [ ] MCP 客户端三步连通
- [ ] SSE 流式回归
- [ ] 部署四件套实证：health-check、新 JS sha256、jar 内 class、bug-fix log diff

## 9. 回滚方案

- 发版走 `releases/<commit>` 目录，健康检查失败自动回滚（`deploy-shihai` 已有 trap）
- 本次不改表结构，Flyway 12 对已执行迁移只做校验
- 若校验失败**不要**直接 `repair`，先比对 `flyway_schema_history.checksum` 是否因算法变化而变

## 10. 明确不做

| 项 | 原因 |
|---|---|
| POI 5.4.1 → 6.0.0 | 大版本，API 破坏面未知，应与框架升级解耦 |
| Flyway → 13.12.1 | Boot 4.1.2 托管 12.4.0，跳大版本无收益 |
| Java 17 → 21 | 与 Boot 升级正交，混在一起无法定位回归 |
| Spring Boot 4.2 / Spring AI 2.1 | 均未 GA（RC2） |
| 一次性把 Jackson 2 迁到 3 | 26 个文件，可独立成 Phase 3；当前共存可运行 |

---

## 附：复现命令

```bash
mvn versions:display-dependency-updates -DprocessDependencyManagement=false
curl -s https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom
curl -s https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml
# 试验副本
rsync -a --exclude target --exclude .git --exclude node_modules --exclude dist \
  ./ /tmp/spike-boot4/ && cd /tmp/spike-boot4 && mvn -B test
```
