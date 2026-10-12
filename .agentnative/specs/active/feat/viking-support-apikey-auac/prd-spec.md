# Review Summary

- 本次需求: veadk-java 的 Viking KnowledgeBase 与 Viking Memory 数据面访问支持 API key 鉴权，并保持现有 AK/SK 行为兼容。
- 需求类型: backend behavior / SDK contract / authentication / compatibility / NFR。
- 变更面: Functional Requirements, Backend/Data, API Design, NFR / DFX, Traceability; 不涉及: UX Design, Metrics, 数据持久化 schema, 服务端 OpenAPI 语义; 待确认: 无阻塞项。
- 变更面判断依据: 需求澄清明确数据面支持 API key、显式配置优先于环境变量、参考 veadk-python；veadk-java 现状代码中 Viking KnowledgeBase/Memory 依赖 AK/SK wrapper 并在构造阶段做集合管理；veadk-python 已实现 API key 环境变量、空值清理、API-key-only 跳过集合管理预检查和数据面 API key 鉴权。
- 差异判断: 产品形态差异不涉及，当前为 Java SDK 能力；站点/地域差异不新增，沿用现有 Viking 访问入口；环境差异涉及显式参数与环境变量配置优先级；版本差异涉及向后兼容要求，不允许破坏既有 AK/SK 用户。依据: 需求澄清、veadk-java README 和 Viking 相关代码、veadk-python 对应 backend 与测试。
- 本次变更关键信息:
  - 新增 Viking KnowledgeBase 查询、Viking Memory 搜索和记忆添加的数据面 API key 鉴权能力。
  - API key 环境变量固定对齐 veadk-python: KnowledgeBase 使用 `DATABASE_VIKING_API_KEY`，Memory 使用 `DATABASE_VIKINGMEM_API_KEY`。
  - 配置优先级为显式 API key 参数 > 环境变量 API key > 现有 AK/SK 或默认回退；显式空值、`None`、`null` 按未配置处理。
  - API-key-only 场景下不因缺少 AK/SK 阻塞数据面能力初始化；集合预检查和自动创建等管理面能力继续依赖 AK/SK 或 IAM。
  - 知识库 `addDoc` / 文档导入链路不纳入本次 API key 数据面强制范围。
- Review 重点:
  - 数据面与管理面边界是否足够清晰。
  - API key 配置入口、环境变量命名和优先级是否与 veadk-python 对齐。
  - 既有 AK/SK 兼容性、API-key-only 初始化和失败返回契约是否可被下游设计实现。
  - 凭据不泄露、单元测试覆盖和真实联调边界是否明确。

## Scope Note

- Implemented sections: Context, Goals and Non-Goals, Glossary, Functional Requirements, Backend/Data, API Design, NFR / DFX, Traceability。
- Not involved: UX Design, Metrics, 数据库/持久化 schema, 前端控制台, Viking 服务端 OpenAPI 语义, 非 Viking KnowledgeBase/Memory 能力。
- Needs confirmation: 无阻塞项。
- Reason: 需求澄清和代码证据均指向 Java SDK 内 Viking 数据面鉴权能力扩展，需明确 SDK 行为和兼容性，但不涉及 UI、服务端接口或数据模型变更。

# Spec: veadk-java Viking 支持 API key 请求

## 1. Context

- 需求来源: Meego 工作项 `7379276505` 及共享产物 `artifacts/AI-Context Collect/需求澄清/需求澄清`。
- 现状: veadk-java 的 Viking KnowledgeBase 与 Viking Memory 使用 AK/SK 访问；README 中运行 Viking Memory 或 Viking Knowledgebase 示例要求配置 `VOLCENGINE_ACCESS_KEY` 与 `VOLCENGINE_SECRET_KEY`。
- 业务驱动: Java SDK 用户希望在查询知识库、搜索记忆、添加记忆等数据面场景使用 API key，降低必须配置 AK/SK 的门槛，并与 veadk-python 行为保持一致。
- 主要用户: 使用 veadk-java 集成 Viking KnowledgeBase 或 Viking Memory 的 Java SDK 开发者、通过环境变量注入凭据的运行环境维护者。
- 证据:
  - veadk-java `VikingKnowledgebaseConfig` 当前只承载 AK/SK、rerank 和 chunk diffusion 配置。
  - veadk-java `VikingKnowledgebaseBackend` 和 `VikingMemoryService` 构造阶段会使用 wrapper 做集合存在性检查和自动创建。
  - veadk-java `VikingKnowledgebaseWrapper` 与 `VikingMemoryWrapper` 当前通过 AK/SK wrapper 调用查询、添加、搜索和集合管理接口。
  - veadk-python 已通过 `DATABASE_VIKING_API_KEY` / `DATABASE_VIKINGMEM_API_KEY`、显式参数优先级、空值清理、API-key-only 跳过管理面预检查和数据面 API key 鉴权实现同类能力。

## 2. Goals and Non-Goals

### Goals

- G-001: Viking KnowledgeBase 查询可使用 API key 完成数据面鉴权。
- G-002: Viking Memory 搜索和记忆添加可使用 API key 完成数据面鉴权。
- G-003: 显式 API key 配置优先于环境变量 API key；无有效 API key 时保持现有 AK/SK 行为。
- G-004: API-key-only 场景下 SDK 可构造并发起明确的数据面调用，不因缺少 AK/SK 的集合管理能力提前失败。
- G-005: API key、AK/SK、session token 和完整 Authorization 头不得出现在日志、异常、测试输出或示例真实值中。
- G-006: 单元测试覆盖配置解析、鉴权分支、兼容性和凭据不泄露；真实 Viking 服务联调作为可选集成验证。

### Non-Goals

- NG-001: 不改造 Viking 服务端、AgentKit 控制面、Blitz 数据面或服务端 OpenAPI 语义。
- NG-002: 不把集合查询、集合创建、TOS 上传、文档导入等管理面能力全面改为 API key 鉴权。
- NG-003: 不要求知识库 `addDoc` / 文档导入链路在仅 API key 场景下可完成。
- NG-004: 不扩大到 OpenSearch、Mem0、OpenViking、WebSearch、Ark 模型、AgentKit Tool 或 TLS Trace 等非本次 Viking KnowledgeBase/Memory 场景。
- NG-005: 不新增前端 UI、控制台配置页、Dashboard 或产品埋点。

## 3. Glossary

- API key: Viking 数据面访问凭据。本需求中 KnowledgeBase 对应 `DATABASE_VIKING_API_KEY`，Memory 对应 `DATABASE_VIKINGMEM_API_KEY`。
- AK/SK: Volcengine AccessKey / SecretKey，用于现有 Viking 管理面和签名访问能力。
- 数据面: 本需求覆盖的业务调用路径，包括 KnowledgeBase 查询、Memory 搜索和 Memory 记忆添加。
- 管理面: 集合查询、集合创建、TOS 上传、知识库文档添加/导入等资源管理或准备链路。
- API-key-only: 仅配置有效 Viking API key，未配置 AK/SK 或 IAM 管理凭据的运行状态。

## 4. Functional Requirements

### REQ-001: Viking KnowledgeBase 查询支持 API key

- User Story: As a Java SDK developer, I want to query Viking KnowledgeBase with an API key, so that I can use data-plane search without configuring AK/SK.
- Priority: P0
- Description: 当 Viking KnowledgeBase 配置了有效 API key 时，知识库查询数据面调用使用 API key 鉴权，并返回与现有 AK/SK 模式一致结构的结果。

Acceptance Requirements (EARS):
- REQ-001.1: The system shall support an explicit Viking KnowledgeBase API key configuration for data-plane knowledge search.
- REQ-001.2: When no explicit KnowledgeBase API key is provided and `DATABASE_VIKING_API_KEY` is configured with a valid value, the system shall use the environment API key for knowledge search.
- REQ-001.3: When a KnowledgeBase API key is selected, the system shall authenticate the knowledge search data-plane request with Bearer token semantics equivalent to veadk-python.
- REQ-001.4: When KnowledgeBase search succeeds with API key auth, the system shall return the same public result shape as the current AK/SK search path.
- REQ-001.5: If KnowledgeBase search fails because the API key is invalid, expired, unauthorized, or the service returns an error, then the system shall follow the existing Java public method failure contract and must not expose credentials.

Gherkin:
```gherkin
Scenario: KnowledgeBase search uses API key from environment
  Given a Java application uses Viking KnowledgeBase
  And DATABASE_VIKING_API_KEY contains a valid API key
  And VOLCENGINE_ACCESS_KEY and VOLCENGINE_SECRET_KEY are not configured
  When the application searches the knowledgebase
  Then the SDK authenticates the search request with the API key
  And the application receives the same result structure as AK/SK search
```

### REQ-002: Viking Memory 搜索支持 API key

- User Story: As a Java SDK developer, I want to search Viking Memory with an API key, so that long-term memory retrieval works without AK/SK data-plane credentials.
- Priority: P0
- Description: 当 Viking Memory 配置了有效 API key 时，记忆搜索数据面调用使用 API key 鉴权，并保持现有 `SearchMemoryResponse` 结果结构。

Acceptance Requirements (EARS):
- REQ-002.1: The system shall support an explicit Viking Memory API key configuration for memory data-plane operations.
- REQ-002.2: When no explicit Memory API key is provided and `DATABASE_VIKINGMEM_API_KEY` is configured with a valid value, the system shall use the environment API key for memory search.
- REQ-002.3: When a Memory API key is selected, the system shall authenticate memory search using API key auth equivalent to veadk-python.
- REQ-002.4: When memory search succeeds with API key auth, the system shall return a `SearchMemoryResponse` compatible with the current AK/SK path.
- REQ-002.5: If memory search fails because of API key or service errors, then the system shall follow the existing Java failure contract and must not expose credentials.

Gherkin:
```gherkin
Scenario: Memory search uses API key
  Given a Java application uses Viking Memory
  And DATABASE_VIKINGMEM_API_KEY contains a valid API key
  And AK/SK credentials are absent
  When the application searches memory
  Then the SDK authenticates the data-plane request with the API key
  And returns a compatible SearchMemoryResponse
```

### REQ-003: Viking Memory 记忆添加支持 API key

- User Story: As a Java SDK developer, I want to save session memory with an API key, so that auto-save memory workflows do not require AK/SK for data-plane writes.
- Priority: P0
- Description: Viking Memory 的记忆添加数据面能力在配置 API key 后应使用 API key 鉴权，并保持现有添加成功/失败契约。

Acceptance Requirements (EARS):
- REQ-003.1: The system shall use the selected Viking Memory API key for memory add-session data-plane requests.
- REQ-003.2: When a session has no valid user text messages, the system shall preserve the existing behavior and avoid unnecessary data-plane calls.
- REQ-003.3: When memory add succeeds with API key auth, the system shall preserve the current public success contract.
- REQ-003.4: If memory add fails because of API key or service errors, then the system shall follow the existing Java failure contract and must not expose credentials.

Gherkin:
```gherkin
Scenario: Memory add uses API key
  Given a Java application has Viking Memory configured with an explicit API key
  And a session contains valid user messages
  When the application saves the session to memory
  Then the SDK authenticates the add-session request with the API key
  And the public success or failure contract remains compatible
```

### REQ-004: API key 配置优先级与空值处理

- User Story: As a Java SDK developer, I want explicit configuration to override environment variables, so that deployment-level defaults can be overridden safely by code.
- Priority: P0
- Description: API key 配置解析应对齐 veadk-python，显式有效值优先；显式空值或占位值视为未配置并回退环境变量。

Acceptance Requirements (EARS):
- REQ-004.1: The system shall prefer an explicit API key parameter over the corresponding environment variable.
- REQ-004.2: When the explicit API key is blank, `null`, `None`, or `null` as a string ignoring case and surrounding whitespace, the system shall treat it as not configured.
- REQ-004.3: When the explicit API key is treated as not configured, the system shall fall back to `DATABASE_VIKING_API_KEY` for KnowledgeBase or `DATABASE_VIKINGMEM_API_KEY` for Memory.
- REQ-004.4: When neither explicit nor environment API key is configured, the system shall preserve the current AK/SK credential resolution behavior.
- REQ-004.5: If neither a usable API key nor required AK/SK or IAM fallback is available for a path that needs authentication, then the system shall fail with a non-sensitive missing-configuration message.

Gherkin:
```gherkin
Scenario: Explicit API key overrides environment
  Given DATABASE_VIKING_API_KEY is set to "env-key"
  And a Java application explicitly configures "explicit-key"
  When the application searches Viking KnowledgeBase
  Then the SDK uses "explicit-key"
```

### REQ-005: API-key-only 场景的数据面初始化边界

- User Story: As a Java SDK developer, I want API-key-only configurations to construct data-plane clients, so that SDK initialization does not fail before a data-plane request can reach Viking.
- Priority: P0
- Description: 仅配置 API key 且无 AK/SK 时，SDK 不应执行需要管理面凭据的集合预检查或自动创建；数据面调用结果由 Viking 服务侧返回。

Acceptance Requirements (EARS):
- REQ-005.1: When an API key is configured and AK/SK or IAM management credentials are not available, the system shall skip collection existence precheck for Viking KnowledgeBase initialization.
- REQ-005.2: When an API key is configured and AK/SK or IAM management credentials are not available, the system shall skip collection existence precheck and automatic collection creation for Viking Memory initialization.
- REQ-005.3: When API-key-only initialization completes, the system shall allow KnowledgeBase search, Memory search, and Memory add data-plane calls to be attempted.
- REQ-005.4: If the target collection does not exist or is not authorized in API-key-only mode, then the system shall surface a non-sensitive failure according to the existing Java public method contract.
- REQ-005.5: When AK/SK or IAM management credentials are available, the system may keep the current management precheck and auto-create behavior.

Gherkin:
```gherkin
Scenario: API-key-only initialization skips collection management
  Given a Viking Memory API key is configured
  And AK/SK credentials are absent
  When the Java application constructs Viking Memory
  Then the SDK does not require collection management credentials during construction
  And memory search or add can proceed to the data-plane request
```

### REQ-006: 保持 AK/SK 兼容和非范围能力不变

- User Story: As an existing veadk-java user, I want my current AK/SK Viking setup to keep working after upgrade, so that I can adopt the new version without code or credential migration.
- Priority: P0
- Description: 未配置 API key 时，Viking KnowledgeBase 和 Viking Memory 必须继续使用现有 AK/SK 行为；非本次 Viking 数据面能力不受影响。

Acceptance Requirements (EARS):
- REQ-006.1: When no Viking API key is configured and AK/SK credentials are configured, the system shall keep the current AK/SK behavior for KnowledgeBase search, KnowledgeBase `addDoc`, Memory search, Memory add, and management precheck paths.
- REQ-006.2: When both API key and AK/SK are configured, the system shall use API key only for the covered data-plane paths and shall keep AK/SK or IAM for management paths.
- REQ-006.3: The system shall not change public result types, method names, default rerank behavior, default chunk diffusion count, default memory event type, or collection naming validation except where explicitly required for API key support.
- REQ-006.4: The system shall not change OpenSearch, Mem0, OpenViking, WebSearch, Ark model, AgentKit Tool, TLS Trace, or unrelated credential resolution behavior.

Gherkin:
```gherkin
Scenario: Existing AK/SK user remains compatible
  Given a Java application uses Viking KnowledgeBase with AK/SK
  And no Viking API key is configured
  When the application searches the knowledgebase
  Then the SDK uses the existing AK/SK path
  And public behavior remains unchanged
```

### REQ-007: 知识库文档添加边界

- User Story: As a Java SDK developer, I want the API key scope to be clear, so that I know which KnowledgeBase operations still require AK/SK or IAM.
- Priority: P1
- Description: 本次不要求 KnowledgeBase `addDoc` / 文档导入链路支持 API key-only 成功，因为该链路涉及 TOS 上传和管理面文档添加能力。

Acceptance Requirements (EARS):
- REQ-007.1: The system shall document that KnowledgeBase `addDoc` / document ingestion is not part of the required API key data-plane scope for this change.
- REQ-007.2: When AK/SK or IAM management credentials are available, KnowledgeBase `addDoc` shall preserve existing behavior.
- REQ-007.3: When only API key is configured, KnowledgeBase `addDoc` may fail according to its current management credential requirements, but must not expose credentials.

Gherkin:
```gherkin
Scenario: KnowledgeBase addDoc remains management-scoped
  Given only DATABASE_VIKING_API_KEY is configured
  When the application attempts KnowledgeBase addDoc
  Then the SDK is not required to complete addDoc using API key
  And any failure message is non-sensitive
```

## 5. Backend/Data

### Behavior and Validation Rules

- Viking API key values must be normalized before use:
  - trim surrounding whitespace;
  - treat empty string, Java `null`, string `None`, and string `null` as not configured;
  - preserve all other non-empty values as opaque credentials.
- KnowledgeBase and Memory use separate API key sources and must not accidentally share environment variables.
- Existing collection/app name validation remains in force.
- Existing result mapping remains in force:
  - KnowledgeBase search returns current `KnowledgebaseEntry` list shape.
  - Memory search returns current `SearchMemoryResponse` shape.
  - Memory add preserves current completion/error behavior.

### Data-Plane and Management-Plane Boundary

- Data-plane paths required to support API key:
  - Viking KnowledgeBase search.
  - Viking Memory search.
  - Viking Memory add session / add memory.
- Management-plane paths not required to support API key in this change:
  - KnowledgeBase collection status / collection creation / collection deletion.
  - KnowledgeBase `addDoc`, document import, TOS upload, document list/delete and chunk list management actions.
  - Memory collection status / collection creation and other collection management actions.
- When both API key and management credentials exist, data-plane paths use API key and management paths use AK/SK or IAM, matching veadk-python behavior.
- When only API key exists, initialization skips management precheck and allows data-plane calls to reach Viking; service-side collection existence and authorization remain Viking responsibilities.

### Failure Handling

- API key invalid, expired, unauthorized, collection missing, network timeout, service unavailable, and response parse failure must not leak API key, AK/SK, session token, or Authorization header.
- Failure behavior should preserve current public contracts:
  - KnowledgeBase search failure returns empty results or existing error behavior as currently defined.
  - Memory search and add follow existing RxJava public method behavior.
  - Management-path failures keep existing boolean/exception behavior.
- Exact Viking service-side permission model and error codes are treated as upstream service facts; this Spec requires non-sensitive propagation sufficient for troubleshooting, not new service-side semantics.

## 6. API Design

This requirement changes the Java SDK public configuration contract, not a service-side OpenAPI contract.

### SDK Configuration Contract

- KnowledgeBase API key configuration:
  - explicit parameter on the Viking KnowledgeBase configuration or equivalent public builder entry;
  - environment fallback: `DATABASE_VIKING_API_KEY`.
- Memory API key configuration:
  - explicit parameter on the Viking Memory configuration or equivalent public builder entry;
  - environment fallback: `DATABASE_VIKINGMEM_API_KEY`.
- Precedence:
  - explicit effective API key > environment effective API key > existing AK/SK/IAM fallback.
- Empty value semantics:
  - blank, Java `null`, string `None`, and string `null` are not effective API keys.
- Compatibility:
  - Existing constructors, builders, and examples that use AK/SK continue to compile and behave as before.
  - New API key configuration must be additive and must not require existing users to migrate.

### Authentication Expectations

- KnowledgeBase API key data-plane requests use Bearer token semantics consistent with veadk-python tests.
- Memory API key data-plane requests use Viking Memory SDK/API key auth semantics consistent with veadk-python.
- Management requests must not silently switch to API key when AK/SK or IAM is required.

### Documentation and Examples

- README or equivalent docs should list the new environment variables under Viking KnowledgeBase/Memory credentials:
  - `DATABASE_VIKING_API_KEY`: Viking KnowledgeBase data-plane API key.
  - `DATABASE_VIKINGMEM_API_KEY`: Viking Memory data-plane API key.
- Documentation must state the data-plane-only scope and `addDoc` / collection management boundary.
- Example values must use placeholders and must not include real credentials.

## 7. UX Design

本需求不涉及前端 UX 变更。

## 8. Metrics

本需求不涉及新增或变更埋点、日志、Dashboard 或指标口径。

## 9. NFR / DFX

### Compatibility

- This is an additive SDK credential capability.
- Existing AK/SK users must not experience constructor, method, result-shape, default-config, or runtime behavior regressions when no API key is configured.
- When API key and AK/SK coexist, management/data-plane split must be deterministic and documented.

### Security and Compliance

- Credentials are sensitive data.
- Logs, exceptions, assertion messages, examples, and debug output must not include:
  - raw API key;
  - AK/SK;
  - session token;
  - full `Authorization` header.
- Tests should include at least one negative assertion that API key material is not emitted in failure messages or logs when feasible.

### Reliability

- API-key-only initialization should not fail early because management credentials are absent.
- Data-plane failures should remain attributable through non-sensitive service messages and request context, while preserving public method contracts.
- Network and service errors must not change unrelated capabilities.

### Performance

- API key selection and normalization are local operations and should not add external calls.
- API-key-only initialization should reduce unnecessary management precheck calls when AK/SK is absent.

### Test and Verification Scope

- Required repository tests:
  - KnowledgeBase API key environment fallback.
  - KnowledgeBase explicit API key priority over environment.
  - KnowledgeBase blank/`None`/`null` explicit value falls back to environment.
  - KnowledgeBase search uses API key auth for data-plane request.
  - KnowledgeBase API-key-only construction skips collection management precheck.
  - Memory API key environment fallback.
  - Memory explicit API key priority over environment.
  - Memory blank/`None`/`null` explicit value falls back to environment.
  - Memory search uses API key auth.
  - Memory add uses API key auth.
  - AK/SK path remains compatible when API key is absent.
  - Non-sensitive failure handling for API key auth errors.
- Standard validation should include the relevant Maven unit tests for Viking KnowledgeBase, Viking Memory, and EnvUtil/config parsing.
- Real Viking service integration is optional because it requires valid API key and AK/SK credentials; if run, it should verify one KnowledgeBase search, one Memory search, and one Memory add against a prepared collection.

### Rollout and Rollback

- Rollout is SDK-release based and additive.
- Rollback path is to omit API key configuration and continue using AK/SK, or revert to the previous SDK version if an implementation regression is found.
- No data migration, backfill, or schema rollback is required.

## Open Questions

无阻塞 Open Questions。Viking 服务侧 API key 权限模型、错误码枚举和真实联调账号由服务侧和测试环境提供，不改变本 Spec 的 SDK 行为边界。

## Traceability

| REQ ID | Area | Evidence | QA focus |
|---|---|---|---|
| REQ-001 | KnowledgeBase data-plane auth | 需求澄清; veadk-python `VikingDBKnowledgeBackend.search`; veadk-java `VikingKnowledgebaseWrapper.searchKnowledge` | API key search auth, result shape, failure contract |
| REQ-002 | Memory search auth | 需求澄清; veadk-python `VikingDBLTMBackend.search_memory`; veadk-java `VikingMemoryWrapper.searchMemory` | API key auth, `SearchMemoryResponse` compatibility |
| REQ-003 | Memory add auth | 需求澄清; veadk-python `VikingDBLTMBackend.save_memory`; veadk-java `VikingMemoryWrapper.addSession` | API key add-session auth, empty-message behavior |
| REQ-004 | Config precedence | 用户确认; veadk-python `_clean_api_key` and env tests | explicit > env, empty values, AK/SK fallback |
| REQ-005 | API-key-only initialization | 用户确认; veadk-python API-key-only skip tests; veadk-java constructor precheck evidence | skip management precheck, allow data-plane calls |
| REQ-006 | AK/SK compatibility | veadk-java current tests and README | existing AK/SK tests unchanged, unrelated modules unaffected |
| REQ-007 | addDoc boundary | 用户确认; veadk-python add/doc management code; veadk-java `addDoc` wrapper | `addDoc` remains management-scoped, docs clarify boundary |
