---
spec_id: "veadk-java-viking-apikey"
title: "veadk-java Viking API key data-plane auth backend design"
status: "draft"
schema_version: 1
linked_spec: "prd-spec.md"
created_at: "2026-10-13"
updated_at: "2026-10-13"
---

# Design: veadk-java Viking 支持 API key 请求

## 0. 输入与职责边界

### 0.1 已读取输入

| 输入 | 路径 | 结论 |
|---|---|---|
| 需求澄清 | `artifacts/AI-Context Collect/需求澄清/需求澄清` | 明确数据面支持 API key，环境变量为 `DATABASE_VIKING_API_KEY` / `DATABASE_VIKINGMEM_API_KEY`，显式参数优先于环境变量。 |
| Spec | `artifacts/AI+Human-Spec设计/spec-r1-a1/prd-spec.md` | REQ-001 至 REQ-007 均为当前设计来源。 |
| Spec Review | `artifacts/Spec Review/spec-review-r1-a1/result.yaml` | `PASS` / `NONE`，无阻塞 finding。 |
| 后端设计 Review 返修 | `artifacts/后端设计Review/backend-design-review-r1-a1/result.yaml` | P1 `BDR-001` 要求 Viking Memory 显式 API key 必须提供用户可调用的 public 配置入口，内部/package-private 注入只能作为测试或实现细节。 |
| 仓库清单 | `repos/manifest.yaml` | 当前组件仓为 `repos/volcengine_veadk-java`，分支 `feat/viking-support-apikey-auac`。 |
| 仓库约定 | `README.md`、`pom.xml`、`core/pom.xml` | Maven 多模块，JDK 17，核心代码在 `core`，测试使用 JUnit 5、Mockito、junit-pioneer、AssertJ。仓库内未见 `AGENTS.md` 或 `.agentnative/templates/default/*`。 |
| 参考实现 | `/tmp/veadk-python-ref/veadk/.../vikingdb_knowledge_backend.py`、`.../vikingdb_memory_backend.py` | 仅参考 API key 清洗、环境变量、数据面 Bearer 请求、API-key-only 跳过管理预检查；不照搬 Python 专属 Pydantic / SDK 结构。 |
| 公共规范 | `general-development-standards` 的 OpenAPI/API 契约资料 | 本需求不是新增服务端 OpenAPI/proto；适用点为配置空值、兼容性、鉴权边界、SDK 验证和敏感信息不外泄。 |

### 0.2 当前 Repo 职责

当前 Repo 只负责 veadk-java SDK 内 Viking KnowledgeBase 与 Viking Memory 数据面鉴权能力：

- KnowledgeBase search 支持 API key。
- Viking Memory search 支持 API key。
- Viking Memory add session 支持 API key。
- API key 配置解析遵循显式参数优先于环境变量。
- API-key-only 初始化跳过需要 AK/SK 的集合管理预检查或自动创建。
- 未配置 API key 时保留现有 AK/SK 行为。

不负责：

- Viking 服务端、AgentKit 控制面、Blitz 数据面、TOP/OpenAPI/proto 变更。
- 数据库 schema、持久化迁移、前端 UI、产品埋点。
- KnowledgeBase `addDoc` / TOS 上传 / 文档导入的 API key-only 成功能力。
- OpenSearch、Mem0、OpenViking、WebSearch、Ark 模型、AgentKit Tool、TLS Trace 等非本次 Viking 能力。

## 1. 总体方案

在 veadk-java 中新增一条 additive 的 Viking API key credential path：

1. 在 `EnvUtil` 增加 Viking KnowledgeBase 与 Viking Memory API key 环境变量读取与清洗能力。
2. 在 `VikingKnowledgebaseConfig` 增加 `apiKey` 字段和 builder，保持既有构造器可用。
3. 在 `KnowledgeBase.Builder` 增加 `vikingApiKey(String)` 或等价显式入口，仅用于 `backend("viking")`。
4. 在 `VikingMemoryService` 增加 `public VikingMemoryService(String appName, String apiKey)`，作为 Java SDK 用户显式配置 Memory API key 的 public 入口；保留一参构造器并委托到同一解析逻辑。
5. 在 `VikingKnowledgebaseWrapper` 与 `VikingMemoryWrapper` 中拆分管理面 AK/SK 与数据面 API key：
   - 管理面 `GetCollection` / `CreateCollection` / `AddDoc` 继续使用现有 Volc SDK `json(...)` AK/SK 签名路径。
   - 数据面 `SearchKnowledge` / `SearchMemory` / `AddSession` 当存在有效 API key 时使用 JDK `HttpClient` 构造 Bearer 请求。
   - API key 不存在时继续使用现有 `json(...)` AK/SK 路径。
6. 在 `VikingKnowledgebaseBackend` 和 `VikingMemoryService` 构造阶段，若有效 API key 存在且 AK/SK 管理凭据不可用，则跳过 collection precheck/create；若 AK/SK 可用，则保留现有管理面行为。
7. 更新 README 的 Viking credential 说明，明确数据面 API key 与管理面边界。

核心设计原则：

- API key 只影响 Spec 覆盖的数据面路径，避免管理面静默切换。
- 不移除旧构造器、旧静态工厂和旧默认值，保持二进制和源码兼容。
- 不引入新的第三方依赖，复用 JDK 17 `java.net.http.HttpClient` 与现有 `JSONUtil`。
- 日志只允许输出路径、collection、状态等非敏感信息；不得输出 API key、AK/SK 或完整 Authorization。

## 2. 涉及代码层

| 层 | 目录 | 改动范围 | 关联 REQ |
|---|---|---|---|
| Public SDK config | `core/src/main/java/com/volcengine/veadk/knowledgebase/KnowledgeBase.java` | 为 Viking backend 增加显式 API key 入口并传入 `VikingKnowledgebaseConfig`。 | REQ-001, REQ-004, REQ-006 |
| Config | `core/src/main/java/com/volcengine/veadk/knowledgebase/backends/viking/VikingKnowledgebaseConfig.java` | 新增 `apiKey`、`fromEnv` API key fallback、builder；旧构造器保留。 | REQ-001, REQ-004, REQ-006 |
| Env | `core/src/main/java/com/volcengine/veadk/utils/EnvUtil.java` | 新增 `getVikingKnowledgebaseApiKey()`、`getVikingMemoryApiKey()` 与清洗逻辑。 | REQ-004 |
| KnowledgeBase backend | `core/src/main/java/com/volcengine/veadk/knowledgebase/backends/viking/VikingKnowledgebaseBackend.java` | 构造 wrapper 时传入 apiKey；API-key-only 跳过 `ensureCollection()`；search 保持结果映射。 | REQ-001, REQ-005, REQ-006, REQ-007 |
| KnowledgeBase integration | `core/src/main/java/com/volcengine/veadk/integration/vikingknowledgebase/VikingKnowledgebaseWrapper.java` | 新增 API key 数据面 POST helper；`searchKnowledge` 按 apiKey/AK-SK 分支调用。 | REQ-001, REQ-005, REQ-006 |
| Memory service | `core/src/main/java/com/volcengine/veadk/memory/viking/VikingMemoryService.java` | 新增 public 显式 API key 构造入口；构造 wrapper 时按显式值优先传入 Memory API key；API-key-only 跳过 precheck/create；空消息行为不变。 | REQ-002, REQ-003, REQ-004, REQ-005, REQ-006 |
| Memory integration | `core/src/main/java/com/volcengine/veadk/integration/vikingmemory/VikingMemoryWrapper.java` | 新增 API key 数据面 POST helper；`searchMemory` / `addSession` 按 apiKey/AK-SK 分支调用。 | REQ-002, REQ-003, REQ-006 |
| Docs | `README.md` | 补充 `DATABASE_VIKING_API_KEY` / `DATABASE_VIKINGMEM_API_KEY` 与数据面/管理面边界。 | REQ-007 |
| Tests | `core/src/test/java/...` | 补齐配置、分支、请求 header、跳过管理预检查、兼容性、脱敏测试。 | REQ-001 至 REQ-007 |

## 3. 文件清单

| # | 文件路径 | 新增/修改 | 职责 | 关联 spec |
|---|---|---|---|---|
| F-01 | `core/src/main/java/com/volcengine/veadk/utils/EnvUtil.java` | 修改 | 读取并清洗 Viking API key 环境变量。 | REQ-004 |
| F-02 | `core/src/main/java/com/volcengine/veadk/knowledgebase/backends/viking/VikingKnowledgebaseConfig.java` | 修改 | 承载显式 KB API key 与 AK/SK 兼容配置。 | REQ-001, REQ-004, REQ-006 |
| F-03 | `core/src/main/java/com/volcengine/veadk/knowledgebase/KnowledgeBase.java` | 修改 | 对 public builder 暴露 Viking API key 配置入口。 | REQ-001, REQ-004 |
| F-04 | `core/src/main/java/com/volcengine/veadk/knowledgebase/backends/viking/VikingKnowledgebaseBackend.java` | 修改 | API-key-only 初始化边界与 search 数据面分支传递。 | REQ-001, REQ-005, REQ-006, REQ-007 |
| F-05 | `core/src/main/java/com/volcengine/veadk/integration/vikingknowledgebase/VikingKnowledgebaseWrapper.java` | 修改 | KB 数据面 Bearer POST 与 AK/SK 兼容路径。 | REQ-001, REQ-006 |
| F-06 | `core/src/main/java/com/volcengine/veadk/memory/viking/VikingMemoryService.java` | 修改 | 新增用户可调用的 public 显式 API key 构造入口，完成 Memory API key 读取、显式优先级、API-key-only precheck 边界和数据面调用不变形。 | REQ-002, REQ-003, REQ-004, REQ-005, REQ-006 |
| F-07 | `core/src/main/java/com/volcengine/veadk/integration/vikingmemory/VikingMemoryWrapper.java` | 修改 | Memory search/add Bearer POST 与 AK/SK 兼容路径。 | REQ-002, REQ-003, REQ-006 |
| F-08 | `core/src/test/java/com/volcengine/veadk/utils/EnvUtilTest.java` | 修改 | API key 环境变量和清洗单测。 | REQ-004 |
| F-09 | `core/src/test/java/com/volcengine/veadk/knowledgebase/backends/viking/VikingKnowledgebaseBackendTest.java` | 修改 | KB API-key-only precheck 和兼容性单测。 | REQ-001, REQ-005, REQ-006 |
| F-10 | `core/src/test/java/com/volcengine/veadk/integration/vikingknowledgebase/VikingKnowledgebaseWrapperTest.java` | 修改 | KB Bearer header、AK/SK 分支、失败不泄露单测。 | REQ-001, REQ-006 |
| F-11 | `core/src/test/java/com/volcengine/veadk/memory/viking/VikingMemoryServiceTest.java` | 修改 | Memory public 显式 API key 优先级、API-key-only precheck、空消息行为、search/add 兼容单测。 | REQ-002, REQ-003, REQ-004, REQ-005 |
| F-12 | `core/src/test/java/com/volcengine/veadk/integration/vikingmemory/VikingMemoryWrapperTest.java` | 修改 | Memory Bearer header、search/add 分支、失败不泄露单测。 | REQ-002, REQ-003 |
| F-13 | `core/src/test/java/com/volcengine/veadk/knowledgebase/KnowledgeBaseTest.java` | 修改 | public builder 显式 API key 传递测试。 | REQ-004 |
| F-14 | `README.md` | 修改 | 文档说明新增环境变量和 API key 数据面范围。 | REQ-007 |

## 4. 数据模型

无数据库、持久化 schema、DML 或 migration 变更。

配置数据仅在 SDK 进程内解析：

| 配置字段 | 来源 | 清洗规则 | 用途 |
|---|---|---|---|
| KnowledgeBase API key | 显式 `VikingKnowledgebaseConfig.apiKey` / `KnowledgeBase.Builder.vikingApiKey` 或 `DATABASE_VIKING_API_KEY` | trim；空字符串、`None`、`null` 忽略大小写视为未配置。 | `SearchKnowledge` 数据面 Bearer 请求。 |
| Memory API key | public `VikingMemoryService(String appName, String apiKey)` 显式参数或 `DATABASE_VIKINGMEM_API_KEY` | 同上；显式有效值必须优先于环境变量。 | `SearchMemory` / `AddSession` 数据面 Bearer 请求。 |
| AK/SK | 既有显式配置或 `VOLCENGINE_ACCESS_KEY` / `VOLCENGINE_SECRET_KEY` | 维持现有 `EnvUtil` 行为。 | 管理面 precheck/create/addDoc 与无 API key 时的数据面。 |

## 5. API / SDK 契约

本需求不新增服务端 OpenAPI、proto、错误码或 TOP 网关注册。

### 5.1 Public SDK 配置契约

KnowledgeBase：

- `VikingKnowledgebaseConfig` 新增 `apiKey` 字段、getter 和 builder。
- 保留现有构造器 `VikingKnowledgebaseConfig(String accessKey, String secretKey, boolean rerank, int chunkDiffusionCount)`，内部委托到新构造器并令 `apiKey=null`。
- `fromEnv()` 返回配置时不直接要求 AK/SK 必存在；它应读取可选 API key 与可选 AK/SK，再由具体路径决定是否需要管理凭据。
- `KnowledgeBase.Builder` 增加 `vikingApiKey(String apiKey)`，只在 `backend("viking")` 时生效。非 viking backend 忽略该字段或不传递，不改变 OpenSearch。

Memory：

- `VikingMemoryService(String appName)` 保留。
- 必须新增用户可调用的 public 构造入口 `VikingMemoryService(String appName, String apiKey)`，用于显式传入 Memory API key。该 public 入口是满足 REQ-002.1 / REQ-004.1 的 SDK 契约，不得用 package-private 构造器、测试注入入口或仅内部 config 注入替代。
- `VikingMemoryService(String appName)` 委托到同一解析逻辑，等价于显式 API key 为 `null`，因此从 `EnvUtil.getVikingMemoryApiKey()` 读取环境 API key。
- 内部 package-private 构造器、transport/client 注入或 wrapper 注入只允许作为测试 seam 或实现细节，不能作为用户显式配置 API key 的唯一入口。

优先级：

1. 显式有效 API key。
2. 对应环境变量有效 API key。
3. 现有 AK/SK / session token /默认行为。

上述优先级对 KnowledgeBase 与 Memory 分别独立生效；Memory 必须覆盖 `new VikingMemoryService(appName, "explicit-key")` 在 `DATABASE_VIKINGMEM_API_KEY` 同时存在时选择显式值的场景。

### 5.2 数据面请求契约

KnowledgeBase `searchKnowledge`：

- API key 有效时：
  - URL 使用现有 host/path：`https://api-knowledgebase.mlp.cn-beijing.volces.com/api/knowledge/collection/search_knowledge`。
  - Method `POST`。
  - Header 包含 `Accept: application/json`、`Content-Type: application/json`、`Authorization: Bearer <apiKey>`。
  - Body 保持当前 Java 数据结构：`name`、`query`、`limit`、可选 `query_param`、`post_processing`。
  - 响应仍解析 `data.result_list` 并返回当前 `KnowledgebaseEntry` list shape。
- API key 无效或未配置时：
  - 完全沿用当前 Volc SDK `json("SearchKnowledge", null, bodyStr)` AK/SK 路径。

Memory `searchMemory` / `addSession`：

- API key 有效时：
  - 使用现有 memory path：`/api/memory/search`、`/api/memory/session/add`。
  - Header 使用 Bearer API key。
  - Body 保持当前 Java 字段：`collection_name`、`query`、`filter`、`limit` 或 `messages`、`metadata`。
  - search 仍返回当前 `List<MemoryEntry>` 并由 service 包装为 `SearchMemoryResponse`。
  - add 仍返回 boolean，service 的 RxJava `Completable` 行为不改变。
- API key 未配置时沿用 AK/SK `json(...)`。

### 5.3 管理面边界

- `isCollectionExists` / `createCollection` / `addDoc` 不使用 API key。
- 有 API key 且无 AK/SK 时，构造阶段跳过 precheck/create。
- 有 API key 且有 AK/SK 时，构造阶段可保留 precheck/create，数据面仍优先 API key。
- `addDoc` 在 API-key-only 下可能因 AK/SK 缺失失败，文档需说明这是非目标范围。

## 6. 服务编排

### REQ-001: KnowledgeBase search

1. `KnowledgeBase.Builder` 或 `VikingKnowledgebaseConfig.fromEnv()` 解析 API key。
2. `VikingKnowledgebaseBackend` 校验 collectionName。
3. 构造 `VikingKnowledgebaseWrapper(accessKey, secretKey, apiKey)`。
4. 若 `apiKey` 有效且 AK/SK 不完整，跳过 `ensureCollection()`。
5. 调用 `search` 时：
   - blank query 返回空列表，不触发外部调用。
   - API key 有效走 Bearer POST。
   - 否则走现有 AK/SK `json(...)`。
6. 按现有逻辑映射 result list 到 public `KnowledgebaseEntry`。

### REQ-002 / REQ-003: Viking Memory search/add

1. `VikingMemoryService` 校验 appName。
2. 一参构造器将显式 API key 视为 `null`；二参 public 构造器接收调用方传入的显式 API key。
3. 解析 `DATABASE_VIKINGMEM_MEMORY_TYPE`，保持当前逗号拆分默认行为。
4. 选择 Memory API key：清洗后的显式参数有效时使用显式值；显式值为空、`None`、`null` 或未传时再读取 `DATABASE_VIKINGMEM_API_KEY`；均无有效值时回退 AK/SK。
5. 构造 `VikingMemoryWrapper(accessKey, secretKey, selectedApiKey)`。
6. 若 selectedApiKey 有效且 AK/SK 不完整，跳过 collection precheck/create。
7. `addSessionToMemory` 继续只收集有效 user text message；无有效 message 直接 complete。
8. `searchMemory` / `addSession` 在 wrapper 内按 API key / AK-SK 分支请求并保持 public result shape。

## 7. 状态机、异步任务与并发

无状态机或异步任务变更。

并发影响：

- API key 解析为 immutable config field，不在请求过程中修改。
- JDK `HttpClient` 可作为 wrapper 实例字段复用；测试中通过 package-private transport/client 注入避免全局静态状态。此类 package-private 注入只服务测试可控性，不承担 Memory API key 的 public 用户配置契约。
- 不新增缓存、重试队列或后台线程。

## 8. 外部依赖

| DEP | 系统 | 客户端文件 | 超时/重试 |
|---|---|---|---|
| DEP-001 | Viking KnowledgeBase data-plane | `VikingKnowledgebaseWrapper` | API key HTTP helper 使用 5s 连接/请求超时，对齐现有 `ServiceInfo` 5000ms；不新增自动重试。 |
| DEP-002 | Viking Memory data-plane | `VikingMemoryWrapper` | 同上。 |
| DEP-003 | Viking management-plane | 现有 Volc SDK `BaseServiceImpl` | 保持现状；仅 AK/SK 或 IAM 路径。 |

不新增 Maven 依赖。

## 9. 配置与 Feature Gate

| 配置键 / Gate 名 | 默认值 | 用途 |
|---|---|---|
| `DATABASE_VIKING_API_KEY` | 未配置 | KnowledgeBase search API key 环境变量。 |
| `DATABASE_VIKINGMEM_API_KEY` | 未配置 | Memory search/add API key 环境变量。 |

无需 Feature Gate：

- 这是 SDK 进程内 additive 能力。
- 未配置 API key 时旧行为保持。
- 回滚可通过不配置 API key 或回退 SDK 版本完成。

## 10. 安全、权限与可观测性

权限：

- API key 权限由 Viking 服务端判断；SDK 不新增权限模型。
- collection 不存在、API key 无权限、API key 失效均按现有 public failure contract 反馈，不在 SDK 内伪造服务端错误码。

敏感信息：

- 任何日志、异常、测试断言输出不得包含 API key、AK/SK、session token 或完整 Authorization。
- API key HTTP helper 的失败日志只输出 operation/path/status/code/message 的非敏感摘要。
- 若响应异常对象中可能携带请求 header，不直接 `log.error("..., {}", exception)` 拼完整请求对象；只记录 `exception.getMessage()` 或安全摘要。

可观测性：

- 不新增指标、Trace 或 Dashboard。
- 允许保留现有 debug 日志中的 request body，因为本次 body 不包含 API key；如未来 body 可携带敏感字段需单独评审。

## 11. 兼容性、上线与回滚

兼容性：

- 旧构造器、`KnowledgeBase.viking(String)`、`VikingMemoryService(String)` 继续可用。
- 未配置 API key 时，AK/SK precheck/create/search/addDoc/searchMemory/addSession 行为保持。
- 有 API key 且有 AK/SK 时，数据面优先 API key，管理面仍 AK/SK。
- 非 Viking 模块不读取新增环境变量。

上线策略：

1. 代码实现与单测合入当前 SDK 分支。
2. README 说明新增配置。
3. SDK 发布后用户按需配置 API key 使用。

回滚预案：

- 用户侧回滚：移除 `DATABASE_VIKING_API_KEY` / `DATABASE_VIKINGMEM_API_KEY` 或显式 API key，继续 AK/SK。
- 代码侧回滚：回退本次 SDK commit；无 DB 或服务端状态需恢复。

## 12. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| API key 路径与 AK/SK 路径响应 envelope 细节不完全一致 | 解析失败或空结果 | 单测覆盖 `code/data/result_list` 成功与失败；实现保持当前解析入口，必要时兼容 `data` wrapper。 |
| `fromEnv()` 继续强制读取 AK/SK 导致 API-key-only 构造提前失败 | API-key-only 无法使用 | 将 AK/SK 读取改为 optional；仅管理面实际需要时调用 required getter 或判断不完整后跳过。 |
| 日志误打 Authorization 或 API key | 凭据泄露 | HTTP helper 不输出 header；失败测试断言 secret 不出现在异常/日志摘要。 |
| 新 public builder 字段影响 OpenSearch | 非范围回归 | `vikingApiKey` 只传给 Viking backend；OpenSearch 测试保持。 |
| `addDoc` API-key-only 用户误以为可用 | 使用困惑 | README 明确 `addDoc` / 文档导入仍属管理面，需 AK/SK 或 IAM。 |

## 13. 测试要点

| 场景 | 方法 | 关联 REQ |
|---|---|---|
| KB 显式 API key 优先于环境变量 | `VikingKnowledgebaseConfig` / `KnowledgeBase.Builder` 单测 | REQ-001, REQ-004 |
| KB 空显式 API key 回退环境变量 | `""`、`"None"`、`"null"`、空白单测 | REQ-004 |
| KB API-key-only 构造跳过管理预检查 | mock wrapper，断言不调用 `isCollectionExists` / `createCollection` | REQ-005 |
| KB search Bearer 请求 | 注入 fake HTTP transport，断言 `Authorization: Bearer <key>`，且不调用 AK/SK json 分支 | REQ-001 |
| KB AK/SK 兼容 | API key absent，沿用现有 wrapper `json(...)` 测试 | REQ-006 |
| Memory public 显式 API key 优先于环境变量 | `VikingMemoryService(String appName, String apiKey)` 构造测试，断言显式有效值覆盖 `DATABASE_VIKINGMEM_API_KEY` | REQ-002, REQ-003, REQ-004 |
| Memory 空显式 API key 回退环境变量 | `VikingMemoryService` 构造测试覆盖 `""`、`"None"`、`"null"`、空白和 `null` 参数 | REQ-004 |
| Memory API-key-only 构造跳过 precheck/create | mock wrapper，断言不调用管理面 | REQ-005 |
| Memory search/add Bearer 请求 | fake HTTP transport，分别覆盖 `/api/memory/search` 和 `/api/memory/session/add` | REQ-002, REQ-003 |
| Memory 空消息不调用数据面 | 复用现有 `addSessionToMemory_noValidMessages` 并覆盖 API key wrapper | REQ-003 |
| 凭据不泄露 | API key failure case 中断言异常/日志摘要不包含 secret 或 Authorization | REQ-005 |
| 文档边界 | README 更新 review | REQ-007 |

建议开发验证命令：

- `./mvnw -pl core -DskipTests compile`
- `./mvnw -pl core -Dtest=EnvUtilTest,VikingKnowledgebaseBackendTest,VikingKnowledgebaseWrapperTest,VikingMemoryServiceTest,VikingMemoryWrapperTest,KnowledgeBaseTest test`
- `./mvnw -pl core -Dtest=... -Djacoco.skip=false test jacoco:report` 或仓库已有 coverage 入口，用于开发阶段增量覆盖率证明。

真实 Viking 联调为可选，需要有效 API key / AK/SK 与预置 collection；不能作为普通 CI 强制前置。

## 14. Requirement -> Design -> Task -> Test 追踪

| REQ | Design | Task | Test |
|---|---|---|---|
| REQ-001 | KB config、wrapper Bearer search、结果映射 | T1, T2, T5 | KB env/priority、Bearer request、AK/SK compatibility |
| REQ-002 | Memory public API key 构造入口、wrapper Bearer search | T1, T3, T5 | Memory public explicit constructor、search Bearer request |
| REQ-003 | Memory public API key 构造入口、wrapper Bearer add session、service 空消息保持 | T1, T3, T5 | add Bearer request、no-valid-message no-call |
| REQ-004 | `EnvUtil` 清洗与 KnowledgeBase/Memory 显式优先级 | T1, T5 | blank/None/null fallback、explicit overrides env，Memory public constructor overrides env |
| REQ-005 | API-key-only precheck skip | T2, T3, T5 | KB/Memory constructor skip management precheck |
| REQ-006 | AK/SK fallback 与非范围不变 | T2, T3, T5 | API key absent old tests, OpenSearch/Mem0 untouched |
| REQ-007 | README 和 addDoc 管理面边界 | T4, T5 | README review, addDoc AK/SK existing test |

## 15. 待确认项

无阻塞待确认项。

实现阶段需通过单元测试确认 Volc SDK `BaseServiceImpl` 分支仍保持旧行为；若发现 Viking 服务端 API key 数据面要求额外字段或不同 endpoint，应记录为新事实并回到设计或 Spec 补充。
