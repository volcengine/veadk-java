---
spec_id: "veadk-java-viking-api-key"
title: "veadk-java Viking 数据面支持 API Key 请求"
status: "draft"
template_id: "backend-design"
schema_version: 1
linked_spec: "veadk-java-viking-api-key"
baseline_design: null
depends_on_designs: []
supersedes_designs: []
created_at: "2026-09-29"
updated_at: "2026-09-29"
---

# Design: veadk-java Viking 数据面支持 API Key 请求

> 本设计回答如何在不改变现有 AK/SK 管理能力和公共结果语义的前提下，为 Viking Knowledgebase 查询、Viking Memory 记忆添加与检索增加 API Key 鉴权。

---

# 1. 总体方案

在现有 Viking backend/service 与 wrapper 分层内增加实例级 API Key 配置，并以一个复用 Java 17 `HttpClient` 的内部 API Key 客户端承载三条 Volcengine 数据面请求。配置层先规范化显式值，再回退到各自环境变量；数据面有有效 API Key 时固定使用 `Authorization: Bearer <apiKey>`，否则保持原 AK/SK SDK 请求。collection 检查、自动创建和 Knowledgebase 文档添加始终只使用 AK/SK；API-key-only 实例跳过构造期 collection 管理预检查。已选 API Key 的失败直接向调用方传播，不进行环境变量二次选择或 AK/SK 降级。

上游及事实基线：

- 冻结 Spec：`.agentnative/specs/active/feat/support-apikey-for-viking-h3gs/prd-spec.md`，对应共享输入 `artifacts/workflow-node-artifact-yew505x2ioks15jk10xz/prd-spec.md`；Spec Review 为 `PASS / NONE`。
- 需求澄清：`artifacts/workflow-node-artifact-yew4z5v5s0rv0yt4g3uv/requirement-clarification.md`。
- Python 行为参考：veadk-python 固定提交 `31d2c67be6fb9bd7f3139de42d2615c2c4a73e6f`；仅用于确认配置优先级、API-key-only 初始化和 Bearer 鉴权，不将 Python 后续能力自动纳入范围。
- 当前 Repo 职责：只修改 veadk-java 的 `core` 模块 Viking 配置、数据面传输、初始化分流、单元测试及中英文 README；无跨 Repo 代码交付。
- 非职责：不新增 Viking 业务能力、资源创建权限、BytePlus 支持、数据库、OpenAPI/RPC、前端、Feature Gate、依赖或发布平台操作。

**涉及的代码层**（不涉及的标“无”）：

| 层 | 目录 | 改动范围 | 关联 REQ |
| --- | --- | --- | --- |
| Proto | 无 | 不涉及 OpenAPI/RPC 或生成代码 | N/A |
| SDK 公共入口 | `core/src/main/java/com/volcengine/veadk/knowledgebase/KnowledgeBase.java`、`core/src/main/java/com/volcengine/veadk/memory/viking/VikingMemoryService.java` | 增加可选显式 API Key 入口，保留原 builder/构造器 | REQ-001～REQ-004 |
| 配置 | `core/src/main/java/com/volcengine/veadk/utils/EnvUtil.java`、`core/src/main/java/com/volcengine/veadk/knowledgebase/backends/viking/VikingKnowledgebaseConfig.java` | 规范化并解析两类 API Key，识别完整 AK/SK 对 | REQ-003、REQ-004 |
| Backend/Service | `core/src/main/java/com/volcengine/veadk/knowledgebase/backends/viking/`、`core/src/main/java/com/volcengine/veadk/memory/viking/` | 按凭证能力决定初始化预检，保持业务映射 | REQ-001、REQ-002、REQ-004 |
| Client | `core/src/main/java/com/volcengine/veadk/integration/` | 新增共享 API Key HTTP 客户端；两个 Viking wrapper 仅在数据面分流 | REQ-001、REQ-002、REQ-005 |
| DB Schema | 无 | 无 DDL、DML、ORM 或数据迁移 | N/A |
| 文档 | `README.md`、`README_zh.md` | 同步配置、优先级、能力边界和占位示例 | REQ-006 |

**ADR**：

- ADR-001：使用仓库已采用的 Java 17 `HttpClient` 发起 API Key 数据面请求，不升级现有 Volcengine SDK，也不引入新依赖。当前 Java 仓库未发现可直接表达 Viking Knowledgebase 与 Memory API Key 鉴权的既有客户端，而 `Mem0RuntimeClient` 已提供同技术栈和可注入 transport 的先例。
- ADR-002：共享一个内部 `VikingApiKeyHttpClient`，集中处理 Bearer header、30 秒请求超时、HTTP/JSON/业务错误与敏感信息边界；两个 wrapper 保留各自请求体和结果映射，避免把业务协议抽象到公共客户端。
- ADR-003：本次请求继续使用 Java 现有 collection name 与北京 endpoint，不新增 Python 的 project/resource ID/base URL 公共配置。冻结 Spec 未要求这些能力，且当前 Java AK/SK 路径不存在对应公共契约；若实现联调证明服务端 API Key 契约强制要求额外资源字段，应停止实现并补充设计，不能猜测字段。

# 2. 文件清单

| # | 文件路径 | 新增/修改 | 职责 | 关联 spec |
| --- | --- | --- | --- | --- |
| F-01 | `core/src/main/java/com/volcengine/veadk/utils/EnvUtil.java` | 修改 | 提供两类 API Key 规范化解析与完整 AK/SK 对检测；不改变现有强制 AK/SK getter | REQ-003、REQ-004 |
| F-02 | `core/src/main/java/com/volcengine/veadk/knowledgebase/KnowledgeBase.java` | 修改 | Builder 增加 `apiKey(String)`，仅在创建 Viking backend 时传递 | REQ-001、REQ-003、REQ-004 |
| F-03 | `core/src/main/java/com/volcengine/veadk/knowledgebase/backends/viking/VikingKnowledgebaseConfig.java` | 修改 | 保存已解析 API Key、可选 AK/SK 与鉴权能力；保留旧构造器 | REQ-001、REQ-003、REQ-004 |
| F-04 | `core/src/main/java/com/volcengine/veadk/knowledgebase/backends/viking/VikingKnowledgebaseBackend.java` | 修改 | 创建双鉴权 wrapper；API-key-only 跳过 collection 预检，查询仍映射为现有公共类型 | REQ-001、REQ-004 |
| F-05 | `core/src/main/java/com/volcengine/veadk/integration/VikingApiKeyHttpClient.java` | 新增 | 发送固定 host/path 的 JSON POST，注入 Bearer header，统一超时和安全错误语义，提供包内测试 transport seam | REQ-001、REQ-002、REQ-005 |
| F-06 | `core/src/main/java/com/volcengine/veadk/integration/vikingknowledgebase/VikingKnowledgebaseWrapper.java` | 修改 | API Key 查询分支及严格响应解析；管理与 AK/SK 查询路径保持原实现 | REQ-001、REQ-004、REQ-005 |
| F-07 | `core/src/main/java/com/volcengine/veadk/memory/viking/VikingMemoryService.java` | 修改 | 新增显式 API Key 构造重载，选择双鉴权或 API-key-only 初始化 | REQ-002～REQ-004 |
| F-08 | `core/src/main/java/com/volcengine/veadk/integration/vikingmemory/VikingMemoryWrapper.java` | 修改 | API Key 添加/检索分支及严格响应解析；管理路径保持 AK/SK | REQ-002、REQ-004、REQ-005 |
| F-09 | `core/src/test/java/com/volcengine/veadk/utils/EnvUtilTest.java` | 修改 | 覆盖两类 Key 的环境/无效值/隔离与 AK/SK 对检测 | REQ-003、REQ-004 |
| F-10 | `core/src/test/java/com/volcengine/veadk/knowledgebase/KnowledgeBaseTest.java` | 修改 | 覆盖 Builder 显式值传递和非 Viking 隔离 | REQ-001、REQ-003 |
| F-11 | `core/src/test/java/com/volcengine/veadk/knowledgebase/backends/viking/VikingKnowledgebaseBackendTest.java` | 修改 | 覆盖 API-key-only 跳过预检、双凭证预检、AK/SK 兼容和管理边界 | REQ-001、REQ-004 |
| F-12 | `core/src/test/java/com/volcengine/veadk/integration/VikingApiKeyHttpClientTest.java` | 新增 | 捕获 method/path/header/body，覆盖超时/中断、HTTP、JSON、业务错误及凭证不泄露 | REQ-001、REQ-002、REQ-005 |
| F-13 | `core/src/test/java/com/volcengine/veadk/integration/vikingknowledgebase/VikingKnowledgebaseWrapperTest.java` | 修改 | 覆盖 API Key search 成功、空结果、异常和 AK/SK 原路径 | REQ-001、REQ-004、REQ-005 |
| F-14 | `core/src/test/java/com/volcengine/veadk/memory/viking/VikingMemoryServiceTest.java` | 修改 | 覆盖 API-key-only/双凭证初始化、添加/检索、无有效消息 | REQ-002～REQ-004 |
| F-15 | `core/src/test/java/com/volcengine/veadk/integration/vikingmemory/VikingMemoryWrapperTest.java` | 修改 | 覆盖 API Key add/search 成功、空结果、异常和 AK/SK 原路径 | REQ-002、REQ-004、REQ-005 |
| F-16 | `README.md`、`README_zh.md` | 修改 | 英中同步配置示例和数据面/管理面边界 | REQ-006 |

不修改 `pom.xml`、生成物、非 Viking backend、TOS/Ark/BytePlus 代码。测试实现若能在既有测试文件内完成，不另建同职责测试类；F-12 是新增 transport 必需的独立测试。

# 3. 数据模型

无数据库或持久化数据模型变更。本次新增字段仅存在于 SDK 进程内：

| 进程内字段 | 所属对象 | 来源 | 约束 |
| --- | --- | --- | --- |
| `apiKey` | `KnowledgeBase.Builder` / `VikingKnowledgebaseConfig` | 显式参数优先，否则 `DATABASE_VIKING_API_KEY` | trim 后为空、`none`、`null`（大小写不敏感）均转为 `null`；不得出现在 `toString`、日志或异常中 |
| `apiKey` | `VikingMemoryService` / `VikingMemoryWrapper` | 显式参数优先，否则 `DATABASE_VIKINGMEM_API_KEY` | 与 Knowledgebase 独立；使用相同规范化规则 |
| `hasManagementCredentials` | 配置解析结果 | `VOLCENGINE_ACCESS_KEY` 与 `VOLCENGINE_SECRET_KEY` 均非 blank | 仅决定是否可做构造期管理预检，不改变数据面已选鉴权模式 |

DDL/DML、兼容顺序、数据恢复、GORM/代码生成均为 N/A；没有数据库变更，因此不调用 `db-migration`。

# 4. API 实现

不涉及 Proto、RPC、OpenAPI 或 TOP 网关注册。这里的 API 指 Java SDK 公共入口与对 Viking 的出站 HTTP 调用。

## 4.1 Java SDK 公共入口

| 入口 | 变更 | 输入与选择 | 输出/兼容性 |
| --- | --- | --- | --- |
| `KnowledgeBase.Builder.apiKey(String apiKey)` | 新增可选 fluent 方法 | 仅 `backend("viking")` 创建路径使用；有效显式值优先环境变量 | 不改变 `build()`、`search*` 返回类型；既有调用无需新增参数 |
| `VikingMemoryService(String appName, String apiKey)` | 新增构造重载 | 有效显式值优先 `DATABASE_VIKINGMEM_API_KEY` | 保留 `VikingMemoryService(String appName)` 并委托新解析流程 |
| `VikingKnowledgebaseConfig` 旧四参数构造器 | 保留 | 视为未显式配置 API Key，继续表达 AK/SK 配置 | 避免现有直接构造调用源代码不兼容；新增含 API Key 的构造/工厂供 Builder 使用 |
| 两类 wrapper 旧 `(accessKey, secretKey)` 构造器 | 保留 | 委托新构造器且 API Key 为空 | 既有 AK/SK 直接调用行为不变 |

若 `apiKey(...)` 与非 Viking named backend 同时使用，`build()` 给出不含凭证值的参数错误，避免将 Viking 凭证静默忽略或误传；自定义 `backendInstance` 路径保持现有委托语义，不读取/传递该字段。

## 4.2 出站数据面契约

| 操作 | HTTP 方法与路径 | API Key header | 请求字段保持 | 成功结果 |
| --- | --- | --- | --- | --- |
| Knowledgebase 查询 | `POST /api/knowledge/collection/search_knowledge` | `Authorization: Bearer <apiKey>` | `name`、`query`、`limit`、可选 `query_param`、`post_processing` | 严格解析 `data.result_list` 并复用现有 `KnowledgebaseEntry` 映射；空数组返回空集合 |
| Memory 添加 | `POST /api/memory/session/add` | 同上 | `collection_name`、`messages`、`metadata` | `data.session_id` 存在时成功；缺失视为异常响应 |
| Memory 检索 | `POST /api/memory/search` | 同上 | `collection_name`、`query`、`filter.user_id`、`filter.memory_type`、`limit` | 严格解析 `data.result_list`，沿用现有 `MemoryEntry` 映射；空数组返回空集合 |

固定行为：

1. 目标 origin 沿用当前 wrapper 的 `https://api-knowledgebase.mlp.cn-beijing.volces.com`，API Key 不得发送给任何其他 origin。
2. 请求 `Accept` 与 `Content-Type` 均为 `application/json`，单次请求超时 30 秒；不自动重试，避免非幂等 Memory 添加重复写入。
3. HTTP 非 2xx、无法解析 JSON、响应顶层 `code` 存在且不为 0、或必需结果结构缺失时抛出安全的运行时异常。异常只包含 operation/path、HTTP status、业务 code 和非敏感 request ID（存在时），不拼接 request body、response body、header 或凭证。
4. API Key 路径失败后不调用 AK/SK 分支。正常 `result_list: []` 才映射为空集合；服务失败不能映射为空集合或 `false`。
5. AK/SK 分支继续调用现有 `BaseServiceImpl.json` 及既有解析逻辑，不借本需求扩大其历史错误处理行为。

# 5. 服务编排

### REQ-001：Knowledgebase 查询

1. `KnowledgeBase.Builder` 将原始显式 API Key 交给 `VikingKnowledgebaseConfig`；配置解析为“有效 API Key/无 API Key”和“完整 AK/SK/无完整 AK/SK”两个独立能力。
2. 无 API Key 且无完整 AK/SK 时，在构造 wrapper 前抛出明确错误：Knowledgebase 需要 `DATABASE_VIKING_API_KEY` 或完整 `VOLCENGINE_ACCESS_KEY` + `VOLCENGINE_SECRET_KEY`；消息不包含值。
3. API-key-only 创建仅具数据面客户端的 wrapper，校验 collection name 后跳过 `isCollectionExists/createCollection`。
4. API Key + AK/SK 仍执行既有 collection 预检/创建；查询固定走 API Key，`addDoc` 和管理请求固定走 AK/SK。
5. 仅 AK/SK 完整沿用现有初始化和查询路径。

### REQ-002：Memory 添加与检索

1. 原单参数构造器委托 `VikingMemoryService(appName, null)`；新构造器解析显式值与 `DATABASE_VIKINGMEM_API_KEY`，并独立检测 AK/SK 对。
2. 凭证矩阵与 Knowledgebase 相同；无任何有效凭证时给出 Memory 专属配置缺失错误。
3. API-key-only 跳过 `isCollectionExists/createCollection`；双凭证和 AK/SK-only 保持既有预检/创建。
4. `addSessionToMemory` 继续只收集有效 user 文本消息；无有效消息时不调用 wrapper并正常完成。有 API Key 时 wrapper 对 add/search 固定走 API Key；否则走 AK/SK。
5. 添加、查询异常沿现有 RxJava 边界进入 `Completable` / `Single` error；不得因 API Key 失败返回普通完成或空结果。

**鉴权选择伪代码**：

    apiKey = normalize(explicitApiKey) ?? normalize(dedicatedEnvironmentApiKey)
    hasAkSk = isNotBlank(accessKeyEnv) && isNotBlank(secretKeyEnv)
    if apiKey == null && !hasAkSk: fail with component-specific error
    if hasAkSk: run existing collection management precheck
    else: skip collection management precheck
    dataPlaneCall: apiKey != null ? API_KEY_WITHOUT_FALLBACK : AK_SK

**边界处理**：

- 外部依赖超时：30 秒单请求超时后失败传播；无 SDK 内重试、无鉴权降级。
- 并发：配置、client、wrapper 引用在构造完成后只读；`HttpClient` 可并发复用，不新增共享可变鉴权状态。
- 副作用：Memory 添加可能非幂等，因此 API Key client 不重试；查询也保持单次身份与单次请求。
- 权限：SDK 不扩张 API Key 权限；resource 不存在/不可访问由服务端失败返回，API-key-only 不自动创建。

# 6. 状态机

无业务状态机、审批状态或生命周期持久化变更。仅存在每个实例构造时确定且不在调用中切换的鉴权模式：

| 实例配置状态 | 数据面模式 | 管理能力 | 调用中转移 |
| --- | --- | --- | --- |
| API Key + AK/SK | API Key | AK/SK | 无；API Key 失败不降级 |
| API-key-only | API Key | 禁用预检；显式管理调用报缺少 AK/SK | 无 |
| AK/SK-only | AK/SK | AK/SK | 无 |
| 均无 | 构造失败 | 不可用 | 无 |

# 7. 异步任务

无新增异步任务、队列、定时任务或后台状态流转。现有 RxJava `Completable` / `Single` 只是 SDK 调用接口；本设计不改变 scheduler，也不引入异步重试。Memory 添加的完成/失败继续由现有调用链传播。

# 8. 外部依赖

不新增 Maven 依赖。Viking 服务是既有外部依赖，仅增加同一服务 origin 的 API Key 鉴权传输：

| DEP | 系统 | 客户端文件 | 超时/重试 | 失败语义 |
| --- | --- | --- | --- | --- |
| DEP-001 | Volcengine Viking Knowledgebase | `VikingKnowledgebaseWrapper` + `VikingApiKeyHttpClient` | 30 秒 / 不重试 | HTTP、业务 code、JSON/结构错误均失败传播；空数组不是失败 |
| DEP-002 | Volcengine Viking Memory | `VikingMemoryWrapper` + `VikingApiKeyHttpClient` | 30 秒 / 不重试 | 同上；添加缺少 session ID 视为异常 |

网络中断包装为不含请求体和 header 的运行时异常；线程中断时恢复 interrupt flag。由于不新增 fallback，依赖故障不会以 AK/SK 身份重放请求。

# 9. 配置与 Feature Gate

| 配置键 / 入口 | 默认值 | 用途与生效范围 |
| --- | --- | --- |
| `KnowledgeBase.Builder.apiKey(String)` | `null` | 当前 Viking Knowledgebase 实例的显式 API Key；有效值优先于环境变量 |
| `DATABASE_VIKING_API_KEY` | 未配置 | 仅用于 Viking Knowledgebase 查询 |
| `VikingMemoryService(String, String apiKey)` | 单参数构造时为 `null` | 当前 Viking Memory 实例的显式 API Key |
| `DATABASE_VIKINGMEM_API_KEY` | 未配置 | 仅用于 Viking Memory 添加和检索 |
| `VOLCENGINE_ACCESS_KEY` / `VOLCENGINE_SECRET_KEY` | 沿用现状 | 两者成对时提供管理能力；API Key 缺失时也用于数据面 |

配置解析在实例构造时完成并保存规范化结果，避免同一实例运行期间环境变量变化导致身份切换。两类 API Key 不交叉回退，也不读取 `MODEL_AGENT_API_KEY`。本次无 Feature Gate：API Key 未配置即自然保持 AK/SK 旧路径。

# 10. 上线策略

| 步骤 | 动作 | 备注 |
| --- | --- | --- |
| 1 | 合入 SDK 代码、单测和中英文 README | 无数据库、配置中心、服务端部署或生成步骤 |
| 2 | 发布包含该能力的新 SDK 版本 | 版本说明列出两类环境变量、显式优先级和仅限已有 collection 的数据面边界 |
| 3 | 使用测试 API Key 对已有 collection 做 Knowledgebase query、Memory add/search 冒烟 | 不使用生产 Secret；不通过 SDK 创建测试 collection |
| 4 | 安全技术评审 | 回源核验《ArkClaw/Agentkit 管控日志打印规范》revision 20 与《ArkClaw/Agentkit观测数据脱敏手册》revision 204；精确时效性为 `needs_live_verification` |

**灰度**：SDK 无服务端流量开关。未设置新配置的用户保持 AK/SK；先由明确设置 API Key 的调用方小范围采用并观察 401/403、业务错误码、超时与成功率，不记录凭证或完整 payload。

**回滚预案**：回退 SDK 版本或移除两类 API Key 配置即可恢复纯 AK/SK 行为；无数据/Schema 迁移。已经采用 API-key-only 的用户回滚前必须补齐 AK/SK，否则旧版本会按既有方式在初始化时报配置缺失。

# 11. 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 服务端 API Key 请求实际要求 Java 当前未暴露的 project/resource ID | API Key 请求可能被拒绝 | 当前按冻结 Spec 和 Java 现有 collection name 契约实现；定向测试锁定请求，集成冒烟验证；若确认强制字段则停止并补充设计，不猜测扩展 |
| API Key 被日志、异常或失败断言带出 | 凭证泄漏 | 集中构造 Authorization；禁止打印 header/配置对象/完整 body；异常只含受控元数据；用唯一假 Key 做负向泄露断言 |
| API Key 失败后隐式回落 AK/SK | 以非预期身份执行并掩盖错误配置 | 构造时冻结模式；API Key 分支不调用 AK/SK transport；测试断言零 fallback |
| API-key-only 仍触发 collection 预检 | 合法数据面用户初始化失败 | backend/service 构造测试断言 `isCollectionExists/createCollection` 均未调用 |
| 双凭证时错误地使用 API Key 管理资源 | 权限边界扩大 | 管理方法只保留 AK/SK 路径，API Key client 仅允许三条数据面 path；测试管理调用无 Bearer header |
| Memory add 被自动重试 | 可能重复写入 | HTTP client 不重试；失败直接传播 |
| 将失败误判为空结果或普通完成 | 调用方无法诊断 | API Key path 严格区分空数组与 HTTP/业务/结构失败；Memory add 缺 session ID 失败 |
| 公共构造器改动破坏既有用户 | 编译或运行不兼容 | 只新增 overload/fluent 方法，保留旧构造器和 AK/SK 默认路径；运行完整 core 测试 |

# 12. 测试要点

## 12.1 Requirement → Design → Task → Test 追踪

| Requirement | 设计落点 | Task | 测试/验收证据 |
| --- | --- | --- | --- |
| REQ-001 | §4.1、§4.2 Knowledgebase；§5 REQ-001 | T-1.2、T-2.1～T-2.3 | Builder 入口；Bearer 请求；成功/空结果；API-key-only 无预检；addDoc 不走 API Key |
| REQ-002 | §4.1、§4.2 Memory；§5 REQ-002 | T-1.3、T-3.1～T-3.3 | add/search Bearer 请求；无有效消息零请求；成功/空结果；API-key-only 无预检 |
| REQ-003 | §3、§5、§9 | T-1.1～T-1.3、T-4.2 | 显式优先；空白/none/null 回退；环境无效；两 Key 隔离 |
| REQ-004 | §4.1、§5、§6 | T-1.2、T-1.3、T-4.3 | AK/SK-only、双凭证、无凭证、旧构造方式；管理路径只用 AK/SK |
| REQ-005 | §4.2、§8、§11 | T-2.1～T-4.2 | 401/403、非零 code、资源/5xx、超时、中断、畸形响应；失败非空结果；marker 不泄漏 |
| REQ-006 | §9、§10 | T-5.1 | 英中配置名、优先级、已有 collection、管理边界与占位 Secret 一致 |

## 12.2 定向场景

| 场景 | 方法 | 关联 REQ |
| --- | --- | --- |
| API Key HTTP 契约 | 注入捕获 transport，逐条断言 method、精确 path、JSON 字段、headers 和 timeout | REQ-001、REQ-002 |
| 配置优先级 | JUnit Pioneer 设置/清理环境变量，参数化覆盖 null、空、空白、大小写 none/null、显式/环境冲突和两类 Key 隔离 | REQ-003 |
| Knowledgebase | Mockito 验证 API-key-only 不预检、双凭证仍预检；API Key query 保持结果映射；addDoc 只用管理凭证 | REQ-001、REQ-004 |
| Memory | 验证 API-key-only 不预检；add/search 使用 API Key；无有效消息不发请求；memory type 过滤不变 | REQ-002、REQ-004 |
| 失败区分 | 模拟 HTTP 401/403/404/500、业务非零 code、网络/超时/中断、畸形 JSON、缺少必需结构与正常空数组 | REQ-005 |
| 不降级与不泄漏 | 唯一假 Secret marker；API Key 失败后验证 AK/SK transport 未调用；异常、捕获日志无 marker/Authorization 值 | REQ-003.5、REQ-005.5 |
| 公共兼容性 | 运行原 builder、`KnowledgeBase.viking(String)`、`VikingMemoryService(String)` 与旧 config/wrapper 构造测试 | REQ-004.4 |
| 文档一致性 | 检查两份 README 的配置名、优先级、范围和假凭证一致 | REQ-006 |

## 12.3 实现阶段验证命令与覆盖率

1. `./mvnw spotless:check`。
2. `./mvnw -pl core -Dtest=EnvUtilTest,KnowledgeBaseTest,VikingKnowledgebaseBackendTest,VikingApiKeyHttpClientTest,VikingKnowledgebaseWrapperTest,VikingMemoryServiceTest,VikingMemoryWrapperTest test`。
3. `./mvnw -pl core test`，使用 test 阶段生成的 `core/target/site/jacoco/jacoco.xml` / HTML 作为覆盖来源。
4. 以实现前 commit 到实现 commit 的 `git diff --unified=0` 新增/修改可执行 Java 行为口径，将变更行映射到 JaCoCo line/branch counter，记录覆盖分子、分母、百分比和未覆盖行；增量单测覆盖率必须大于等于 90%。低于门禁时补测并重跑，不得仅引用全模块平均覆盖率。
5. `./mvnw -pl core -DskipTests package` 验证编译、打包和生成一致性；不得产生需提交的未预期文件。

无需真实 API Key 执行单测；真实服务冒烟属于发布采用前验证，只使用受控测试凭证与预先存在的 collection，不创建或修改管理资源。
