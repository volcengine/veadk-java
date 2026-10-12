# Review Summary

- 本次需求: veadk-java 的 Viking KnowledgeBase 查询与 Viking Memory 写入/查询支持 API Key 鉴权，并保持 AK/SK 兼容。
- 需求类型: SDK / backend behavior / API Design / NFR / documentation
- 变更面: Functional Requirements, API Design, Backend/Data, NFR / DFX, Traceability; 不涉及: UX Design, Metrics, 数据库 schema; 待确认: 无
- 变更面判断依据: 需求说明明确数据面 API Key 范围和配置优先级；Java 仓库中 `VikingKnowledgebaseConfig`、`VikingKnowledgebaseBackend`、`VikingKnowledgebaseWrapper`、`VikingMemoryWrapper`、`VikingMemoryService` 当前依赖 AK/SK；README 仅记录 Viking AK/SK 配置；veadk-python 已实现同类环境变量与行为参考。
- 差异判断: 产品形态差异涉及 Java SDK 与 Python SDK 对齐；站点差异不涉及，本期仅 Volcengine；环境差异涉及显式配置与环境变量；版本差异涉及升级后兼容既有 AK/SK 用户。依据: 共享需求说明第 2、3、4、5 节和 Java 仓库证据。
- 本次变更关键信息:
  - Viking KnowledgeBase 查询新增 `DATABASE_VIKING_API_KEY` 及显式 API Key 配置能力，显式配置优先于环境变量。
  - Viking Memory 记忆写入与查询新增 `DATABASE_VIKINGMEM_API_KEY` 及显式 API Key 配置能力，显式配置优先于环境变量。
  - 仅配置 API Key 且未配置 AK/SK 时，初始化不得因集合查询或自动创建缺少 AK/SK 而阻断数据面调用。
  - 知识库文档添加、集合管理、TOS 上传、BytePlus / overseas provider 不纳入本期。
- Review 重点:
  - API Key 数据面范围是否只覆盖知识库查询、记忆写入、记忆查询。
  - 显式参数、环境变量、AK/SK fallback 的优先级和空值处理是否清晰。
  - API-key-only 初始化跳过集合预检和自动创建是否与管理面非目标一致。
  - 安全要求是否覆盖日志、异常、测试和文档示例中的密钥泄露风险。

## Scope Note

- Implemented sections: Context, Goals and Non-Goals, Glossary, Functional Requirements, Backend/Data, API Design, NFR / DFX, Open Questions, Traceability
- Not involved: UX Design, Metrics, 数据库 schema, 服务端鉴权模型, BytePlus / overseas provider
- Needs confirmation: 无
- Reason: 本需求是 veadk-java SDK 内 Viking 数据面鉴权能力扩展，需求说明已确认范围、环境变量、优先级和非目标，仓库证据显示相关面集中在 Java SDK Viking KnowledgeBase 与 Viking Memory。

# Spec: veadk-java Viking 数据面 API Key 支持

## 1. Context

- 需求来源: Meego 工作项 `7379276505`，共享产物“veadk-java Viking 支持 API Key 请求需求澄清”。
- 当前问题: veadk-java 使用 Viking KnowledgeBase 和 Viking Memory 时依赖 `VOLCENGINE_ACCESS_KEY` / `VOLCENGINE_SECRET_KEY`，数据面用户无法仅用 API Key 完成知识库查询或记忆读写。
- 用户角色: 使用 veadk-java SDK 构建 Agent 的 Java 开发者，以及希望降低运行环境 AK/SK 暴露面的 SDK 使用方。
- 业务价值: 数据面调用可以使用更小权限边界的 Viking API Key，同时存量 AK/SK 配置升级后继续可用。
- 仓库基线:
  - `KnowledgeBase.builder()` / `KnowledgeBase.viking(...)` 提供 Java 知识库入口。
  - `VikingKnowledgebaseConfig.fromEnv()` 仅读取 AK/SK。
  - `VikingKnowledgebaseBackend` 初始化会执行集合存在性检查和自动创建。
  - `VikingMemoryService` 初始化会读取 AK/SK 并执行集合存在性检查和自动创建。
  - README / README_zh 中 Viking Memory 与 Viking KnowledgeBase 配置仍只列 AK/SK。

## 2. Goals and Non-Goals

### Goals

- G-001: Java SDK 用户可以通过显式参数或环境变量为 Viking KnowledgeBase 查询配置 API Key。
- G-002: Java SDK 用户可以通过显式参数或环境变量为 Viking Memory 记忆写入与查询配置 API Key。
- G-003: 配置优先级为显式 API Key > API Key 环境变量 > AK/SK 既有配置；显式空值视为未配置。
- G-004: 未配置 API Key 的存量用户保持 AK/SK 行为、返回结构和错误语义兼容。
- G-005: 仅配置 API Key 且未配置 AK/SK 时，初始化不因集合管理预检缺少 AK/SK 而失败，数据面调用由 Viking 服务返回真实结果或错误。
- G-006: README / README_zh 或示例补充 API Key 配置说明，示例只使用占位符。

### Non-Goals

- NG-001: 不改变 Viking 服务端鉴权、权限模型、API Key 发放方式或服务端 API。
- NG-002: 不要求 API Key 覆盖知识库文档添加、集合创建、集合查询、TOS 上传等管理面或导入能力。
- NG-003: 不调整 OpenSearch、Mem0、Redis、local memory、Ark 模型 API Key、AgentKit Tool 等非 Viking 能力。
- NG-004: 不覆盖 BytePlus / overseas provider 的 Viking endpoint、region 或 API Key 环境变量。
- NG-005: 不引入真实密钥到文档、日志、测试或示例输出。

## 3. Glossary

| Term | Definition |
|---|---|
| Viking KnowledgeBase | veadk-java 中基于 Viking 知识库的知识检索 backend，用户通过 KnowledgeBase / LoadKnowledgebaseTool 调用查询能力。 |
| Viking Memory | veadk-java 中基于 Viking Memory 的长期记忆能力，用户通过 VikingMemoryService / ADK memory tool 写入和查询记忆。 |
| 数据面 | 本期指知识库查询、记忆写入、记忆查询三个运行时调用路径。 |
| 管理面 | 本期指集合查询、集合创建、知识库文档添加、TOS 上传等非数据面能力。 |
| 显式配置 | 用户通过 Java SDK 构造参数、builder 或配置对象传入的 API Key。 |
| 环境变量配置 | 用户通过 `DATABASE_VIKING_API_KEY` 或 `DATABASE_VIKINGMEM_API_KEY` 注入的 API Key。 |

## 4. Functional Requirements

### REQ-001: Viking KnowledgeBase 查询支持 API Key

- User Story: As a Java SDK 用户, I want Viking KnowledgeBase 查询可使用 API Key 鉴权, so that 查询数据面不依赖 AK/SK。
- Priority: P0
- Description: 当用户为 Viking KnowledgeBase 配置有效 API Key 时，SDK 在知识库查询数据面优先使用 API Key 鉴权；未配置 API Key 时继续使用 AK/SK。

Acceptance Requirements (EARS):
- REQ-001.1: When 用户调用 Viking KnowledgeBase 查询且存在有效显式 API Key, the system shall 使用显式 API Key 完成查询鉴权。
- REQ-001.2: When 用户未传显式 API Key 且 `DATABASE_VIKING_API_KEY` 存在有效值, the system shall 使用该环境变量完成查询鉴权。
- REQ-001.3: When 显式 API Key 与 `DATABASE_VIKING_API_KEY` 同时存在且值不同, the system shall 使用显式 API Key。
- REQ-001.4: If 未配置有效 API Key, then the system shall 继续使用 AK/SK 或既有默认凭证路径。
- REQ-001.5: If API Key 无权限、过期、格式错误或目标集合不可用, then the system shall 返回可定位的服务端或 SDK 错误/空结果策略，且不得自动回退到 AK/SK。

Gherkin:
```gherkin
Scenario: KnowledgeBase search uses explicit API Key
  Given a Viking KnowledgeBase is configured with an explicit API Key
  And a different DATABASE_VIKING_API_KEY may exist in the environment
  When the user searches the knowledgebase
  Then the SDK uses the explicit API Key for the data-plane request
  And the search response keeps the existing result structure
```

### REQ-002: Viking Memory 写入与查询支持 API Key

- User Story: As a Java SDK 用户, I want Viking Memory add/search 可使用 API Key 鉴权, so that 长期记忆数据面不依赖 AK/SK。
- Priority: P0
- Description: 当用户为 Viking Memory 配置有效 API Key 时，SDK 在记忆写入和记忆查询数据面优先使用 API Key 鉴权；未配置 API Key 时继续使用 AK/SK。

Acceptance Requirements (EARS):
- REQ-002.1: When 用户调用 `addSessionToMemory` 且存在有效显式 Viking Memory API Key, the system shall 使用显式 API Key 完成记忆写入鉴权。
- REQ-002.2: When 用户调用 `searchMemory` 且存在有效显式 Viking Memory API Key, the system shall 使用显式 API Key 完成记忆查询鉴权。
- REQ-002.3: When 用户未传显式 Viking Memory API Key 且 `DATABASE_VIKINGMEM_API_KEY` 存在有效值, the system shall 使用该环境变量完成记忆写入和查询鉴权。
- REQ-002.4: When 显式 Viking Memory API Key 与 `DATABASE_VIKINGMEM_API_KEY` 同时存在且值不同, the system shall 使用显式 API Key。
- REQ-002.5: If 未配置有效 Viking Memory API Key, then the system shall 继续使用 AK/SK。
- REQ-002.6: If API Key 无权限、过期、格式错误或目标集合不可用, then the system shall 返回可定位的服务端或 SDK 错误/空结果策略，且不得自动回退到 AK/SK。

Gherkin:
```gherkin
Scenario: Viking Memory search uses API Key from environment
  Given a Viking Memory service has no explicit API Key
  And DATABASE_VIKINGMEM_API_KEY is configured
  When the user searches memory
  Then the SDK uses DATABASE_VIKINGMEM_API_KEY for the data-plane request
  And the memory response keeps the existing ADK memory response shape
```

### REQ-003: API Key 空值清洗与凭证优先级

- User Story: As a SDK 使用方, I want 空值与多来源凭证有稳定解析规则, so that 部署配置可预测且不会误用密钥。
- Priority: P0
- Description: SDK 对显式 API Key 和环境变量执行一致的空值判断，显式空字符串、`null` 或等价无效值视为未配置。

Acceptance Requirements (EARS):
- REQ-003.1: If 显式 API Key 为 `null`, then the system shall 视为未配置并继续检查环境变量。
- REQ-003.2: If 显式 API Key 为空字符串或仅空白字符, then the system shall 视为未配置并继续检查环境变量。
- REQ-003.3: If 环境变量 API Key 为空字符串或仅空白字符, then the system shall 视为未配置并继续使用 AK/SK fallback。
- REQ-003.4: The system shall not mix `DATABASE_VIKING_API_KEY` and `DATABASE_VIKINGMEM_API_KEY`; 知识库和记忆分别使用各自环境变量。
- REQ-003.5: The system shall not reuse `MODEL_AGENT_API_KEY`、`DATABASE_MEM0_API_KEY` 或其他非 Viking API Key 作为本期 Viking API Key。

Gherkin:
```gherkin
Scenario: Blank explicit API Key falls back to environment
  Given the explicit Viking API Key is blank
  And DATABASE_VIKING_API_KEY is configured
  When the user searches a Viking knowledgebase
  Then the SDK treats the explicit value as missing
  And the SDK uses DATABASE_VIKING_API_KEY
```

### REQ-004: API-key-only 初始化不阻断数据面

- User Story: As a Java SDK 用户, I want 仅配置 API Key 时可初始化 Viking 组件, so that 数据面调用不被管理面 AK/SK 预检阻断。
- Priority: P0
- Description: 当用户仅提供 API Key 且没有 AK/SK 时，SDK 初始化 Viking KnowledgeBase 或 Viking Memory 不执行需要 AK/SK 的集合存在性检查和自动创建。

Acceptance Requirements (EARS):
- REQ-004.1: When Viking KnowledgeBase 仅配置 API Key 且未配置 AK/SK, the system shall 跳过集合存在性检查。
- REQ-004.2: When Viking KnowledgeBase 仅配置 API Key 且未配置 AK/SK, the system shall 跳过自动创建集合。
- REQ-004.3: When Viking Memory 仅配置 API Key 且未配置 AK/SK, the system shall 跳过集合存在性检查。
- REQ-004.4: When Viking Memory 仅配置 API Key 且未配置 AK/SK, the system shall 跳过自动创建集合。
- REQ-004.5: If API-key-only 模式下目标集合不存在或权限不足, then the system shall 由后续数据面调用返回明确的服务端或 SDK 错误/空结果策略。
- REQ-004.6: When AK/SK 存在且 API Key 未配置, the system shall 保持既有集合存在性检查和自动创建行为。

Gherkin:
```gherkin
Scenario: API-key-only KnowledgeBase initialization skips management precheck
  Given DATABASE_VIKING_API_KEY is configured
  And VOLCENGINE_ACCESS_KEY and VOLCENGINE_SECRET_KEY are not configured
  When the user initializes a Viking KnowledgeBase
  Then initialization does not fail due to missing AK/SK
  And collection existence check and auto-create are not required before search
```

### REQ-005: 非数据面能力保持 AK/SK 或既有行为

- User Story: As a SDK 维护者, I want API Key 范围不扩展到管理面, so that 本期变更不误承诺服务端未确认能力。
- Priority: P1
- Description: 本期 API Key 只覆盖知识库查询、记忆写入、记忆查询；其他 Viking 能力继续按既有 AK/SK 或管理面凭证逻辑处理。

Acceptance Requirements (EARS):
- REQ-005.1: The system shall not require Viking KnowledgeBase 文档添加支持 API Key。
- REQ-005.2: The system shall not require Viking 集合创建或集合查询支持 API Key。
- REQ-005.3: The system shall not require TOS 上传或知识库文档导入链路支持 API Key。
- REQ-005.4: When 用户调用非本期范围的管理面能力且仅配置 API Key, the system shall 返回与现有凭证缺失或服务端权限相关的可定位错误，而不是声明 API Key 已覆盖该能力。

Gherkin:
```gherkin
Scenario: Add document remains outside API Key scope
  Given only DATABASE_VIKING_API_KEY is configured
  When the user attempts to add a TOS document to Viking KnowledgeBase
  Then the SDK does not guarantee API Key support for that management path
  And the error remains attributable to missing or insufficient management credentials
```

### REQ-006: 文档、示例与安全输出

- User Story: As a SDK 用户, I want 文档明确说明 API Key 配置方式和范围, so that 我能安全配置数据面能力。
- Priority: P1
- Description: README / README_zh 或示例需说明新增环境变量、显式配置优先级、数据面范围和非目标，并避免泄露真实密钥。

Acceptance Requirements (EARS):
- REQ-006.1: The system shall document `DATABASE_VIKING_API_KEY` for Viking KnowledgeBase 查询。
- REQ-006.2: The system shall document `DATABASE_VIKINGMEM_API_KEY` for Viking Memory 写入和查询。
- REQ-006.3: The system shall document 显式配置优先于环境变量，API Key 未配置时回退 AK/SK。
- REQ-006.4: The system shall document API-key-only 初始化会跳过集合预检和自动创建，目标集合不可用时由数据面返回错误。
- REQ-006.5: The system shall use placeholder values in docs/tests/examples and shall not output real API Key、AK/SK、SessionToken or Bearer Token。

Gherkin:
```gherkin
Scenario: README explains Viking API Key scope
  Given a user reads the Java SDK README
  When they configure Viking KnowledgeBase or Viking Memory
  Then they can identify the correct API Key environment variable
  And they can identify which Viking operations still require AK/SK or management credentials
```

## 5. Backend/Data

- Behavior and validation rules:
  - Viking KnowledgeBase 与 Viking Memory 分别解析各自 API Key 来源，优先级为显式配置 > 环境变量 > AK/SK fallback。
  - 显式值和环境变量值均需执行空值清洗；空字符串、仅空白字符、`null` 均视为未配置。
  - API Key 一旦被选中用于数据面请求，该请求失败时不得再自动回退 AK/SK，避免无权限或过期 API Key 被静默掩盖。
- Data-plane coverage:
  - 覆盖知识库查询。
  - 覆盖记忆写入。
  - 覆盖记忆查询。
- Management-plane boundaries:
  - 知识库文档添加、集合查询、集合创建、TOS 上传不纳入 API Key 覆盖。
  - API-key-only 初始化下跳过集合预检和自动创建，但不保证目标集合存在。
- Storage/model changes:
  - 本需求不涉及 Java SDK 自有数据库、schema、迁移、backfill 或数据保留策略变更。
- Failure handling:
  - 查询无结果保持现有空列表或空响应语义。
  - API Key 鉴权失败、权限不足、过期、格式错误、集合不存在、网络错误或响应解析失败时，遵循现有 SDK 错误/空结果策略，但输出不得包含完整密钥。

## 6. API Design

- Contract type: Java SDK public configuration contract and documented environment variables; no external HTTP / OpenAPI contract change.
- KnowledgeBase SDK surface:
  - Viking KnowledgeBase 用户必须能通过 Java SDK 显式配置 API Key。
  - `KnowledgeBase.viking(...)` 和未显式配置 API Key 的既有用法必须保持兼容。
  - `DATABASE_VIKING_API_KEY` 是 Viking KnowledgeBase API Key 的环境变量入口。
- Memory SDK surface:
  - Viking Memory 用户必须能通过 Java SDK 显式配置 API Key。
  - `new VikingMemoryService(...)` 或等价既有构造方式在未显式配置 API Key 时必须保持兼容。
  - `DATABASE_VIKINGMEM_API_KEY` 是 Viking Memory API Key 的环境变量入口。
- Compatibility:
  - 现有 AK/SK 环境变量 `VOLCENGINE_ACCESS_KEY` / `VOLCENGINE_SECRET_KEY` 继续可用。
  - 现有结果对象、ADK memory response shape、KnowledgeBase 查询结果结构不因鉴权方式变化而改变。
  - 本期不新增或修改对外 HTTP API、OpenAPI、Webhook、CLI 命令或服务端协议。
- SDK/docs impact:
  - README / README_zh 或示例需明确 API Key 环境变量、显式配置优先级、AK/SK fallback 和非目标范围。

## 7. Metrics

本需求不涉及新增或变更埋点、日志、Dashboard 或指标口径。

## 8. NFR / DFX

- Compatibility:
  - 未配置 API Key 的用户升级后保持 AK/SK 访问方式、构造方式、返回结构和现有错误处理兼容。
  - API Key 新增配置不得与 `MODEL_AGENT_API_KEY`、`DATABASE_MEM0_API_KEY` 等现有变量混淆。
- Security:
  - 日志、异常、测试快照、文档示例不得输出完整 API Key、AK/SK、SessionToken 或 Bearer Token。
  - 文档示例使用占位符，不包含真实密钥或可复用凭证。
- Availability and failure isolation:
  - API-key-only 初始化不得因缺少 AK/SK 在构造阶段失败；目标集合不存在或权限不足由数据面调用反馈。
  - 使用 API Key 的数据面失败不得自动降级到 AK/SK，避免错误权限边界被隐藏。
- Regional / provider scope:
  - 本期仅覆盖 Volcengine 现有 Java Viking 能力。
  - BytePlus / overseas provider、endpoint、region 和环境变量不纳入本期。
- Release and rollback:
  - 该能力应作为向后兼容的 SDK 增量发布；回滚时应可恢复到仅 AK/SK 的现有行为，不涉及数据库回滚。

## Open Questions

无。共享需求说明已确认本期范围、变量命名、优先级、API-key-only 初始化和非目标。

## Traceability

| REQ ID | Related section | Evidence | QA focus |
|---|---|---|---|
| REQ-001 | API Design, Backend/Data | 需求说明第 3、5 节；Java `VikingKnowledgebaseWrapper.searchKnowledge` 当前数据面查询 | 显式 API Key、环境变量 API Key、AK/SK fallback、API Key 失败不自动回退 |
| REQ-002 | API Design, Backend/Data | 需求说明第 3、5 节；Java `VikingMemoryWrapper.addSession` / `searchMemory` 当前数据面读写 | 记忆写入、记忆查询、显式优先、环境变量回退 |
| REQ-003 | API Design, NFR / DFX | 需求说明第 3、4、5 节；Java `EnvUtil` 当前已有多类 API Key 环境变量 | 空字符串、空白、`null`、变量隔离 |
| REQ-004 | Backend/Data, NFR / DFX | 需求说明第 2、3、5 节；Java `VikingKnowledgebaseBackend` / `VikingMemoryService` 当前初始化会集合预检与创建 | API-key-only 构造、跳过集合预检、目标集合不可用错误 |
| REQ-005 | Goals and Non-Goals, Backend/Data | 需求说明第 2、3、4、6 节；Java wrapper 存在 AddDoc / CreateCollection / GetCollection 管理面路径 | 文档添加、集合管理、TOS 上传不承诺 API Key |
| REQ-006 | API Design, NFR / DFX | README / README_zh 当前仅列 Viking AK/SK 配置 | 文档变量说明、范围说明、密钥不泄露 |
