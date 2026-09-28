# Review Summary

- 本次需求: 为 veadk-java 的 Viking KnowledgeBase 查询与 Viking Memory 添加、查询能力增加 API Key 数据面鉴权，同时保留 AK/SK 管理面与兼容链路。
- 需求类型: Java SDK 公共配置与行为契约变更、鉴权与安全、向后兼容。
- 变更面: Context、Goals and Non-Goals、Functional Requirements、SDK Contract、Backend/Data Boundary、NFR / DFX、Traceability；不涉及: UX、数据库 Schema、服务端 OpenAPI、Metrics / Dashboard；待确认: 无。
- 变更面判断依据: Meego story 7379276505 及已确认的需求澄清；veadk-java@8a9d00a 的 KnowledgeBase、Viking Memory、配置读取与测试基线；veadk-python@31d2c67 的同类能力；AgentKit 公共 API 兼容与观测数据脱敏规范。
- 差异判断: 产品形态仅涉及 Java SDK；不新增站点差异，沿用 veadk-java 当前 Volcengine Viking 范围；显式参数与环境变量在各运行环境遵循同一优先级；升级后的 SDK 版本新增 API Key 能力，原有 AK/SK 调用保持兼容。依据: 已确认需求范围、当前 Java 仓库能力与 Python 参考实现。
- 本次变更关键信息:
  - KnowledgeBase 查询使用独立的 DATABASE_VIKING_API_KEY，Memory 添加与查询使用独立的 DATABASE_VIKINGMEM_API_KEY。
  - 数据面凭证优先级为有效显式 API Key、高于对应环境变量 API Key、高于现有 AK/SK 回退；管理面始终优先使用 AK/SK。
  - 仅有 API Key 时跳过初始化阶段的集合管理预检查，调用方必须使用已有集合；addDoc(tosUrl) 不进入 API Key-only 范围。
  - API Key、AK/SK、token 与完整连接串不得进入日志、异常、测试输出或文档示例。
- Review 重点:
  - 数据面与管理面的范围边界是否完整且无歧义。
  - 显式参数、环境变量、无效空值与 AK/SK 的优先级是否可测试。
  - 现有构造入口、返回结构与 AK/SK 行为是否保持兼容。
  - 鉴权失败与合法空结果是否可区分，且错误信息不泄露凭证。

## Scope Note

- Implemented sections: Context、Goals and Non-Goals、Glossary、Functional Requirements、SDK Contract、Backend/Data Boundary、NFR / DFX、Traceability。
- Not involved: 前端 UX、数据库 Schema / 数据迁移、服务端 OpenAPI / RPC、埋点、Dashboard、Viking 服务端能力建设、非 Viking KnowledgeBase / Viking Memory 组件。
- Needs confirmation: 无。
- Reason: 已确认需求与仓库证据将变化限定在 veadk-java 客户端 SDK 的配置解析、数据面鉴权选择、初始化边界、兼容性和文档说明。

# Spec: veadk-java Viking 数据面支持 API Key 请求

## 1. Context

### 1.1 背景与用户价值

veadk-java 当前通过 VOLCENGINE_ACCESS_KEY 与 VOLCENGINE_SECRET_KEY 初始化 Viking KnowledgeBase 和 Viking Memory 访问链路，并在初始化阶段检查、必要时创建集合。Java SDK 使用者即使只需访问已有集合的数据面，也必须提供账号 AK/SK。

本需求让 Java SDK 使用者可以通过权限范围更聚焦的 Viking API Key 完成知识库查询、记忆添加和记忆查询，减少仅使用数据面能力时对账号 AK/SK 的依赖，并与 veadk-python 的既有能力保持一致。

### 1.2 用户与核心场景

- Java SDK 使用者使用已有 Viking KnowledgeBase 集合执行知识查询。
- Java SDK 使用者向已有 Viking Memory 集合添加会话记忆，或按用户与查询词检索记忆。
- 已使用 AK/SK 的 Java SDK 使用者升级版本后继续沿用原配置和调用方式。
- 同时具备 API Key 与 AK/SK 的使用者，用 API Key 访问数据面、用 AK/SK 访问管理面。

### 1.3 证据与事实边界

- 已确认需求澄清规定 API Key 仅用于数据面；管理面以及 KnowledgeBase addDoc(tosUrl) 继续依赖 AK/SK。
- veadk-java@8a9d00a 现有 Viking KnowledgeBase 与 Memory 入口只读取 AK/SK，且初始化会执行集合存在性检查与创建。
- veadk-python@31d2c67 已分别使用 DATABASE_VIKING_API_KEY 与 DATABASE_VIKINGMEM_API_KEY，将 API Key 用于对应数据面，并在 API Key-only 场景跳过集合管理预检查。
- 本 Spec 只冻结产品行为和验收契约；API Key 的请求头拼装、客户端选型、类与方法改造属于后续技术设计。

## 2. Goals and Non-Goals

### 2.1 Goals

- G-001: 允许仅持有有效 KnowledgeBase API Key 的调用方查询已有 Viking KnowledgeBase 集合。
- G-002: 允许仅持有有效 Memory API Key 的调用方向已有 Viking Memory 集合添加和查询记忆。
- G-003: 为两类 API Key 同时提供显式参数与环境变量配置，并保证确定、可测试的优先级和空值处理。
- G-004: 保持现有 AK/SK 用户的公开入口、管理面行为和业务返回结构兼容。
- G-005: 使无效凭证、权限不足、资源不存在等失败可定位，同时不泄露任何凭证内容。

### 2.2 Non-Goals

- NG-001: 不允许仅凭 API Key 创建、查询/预检查、更新或删除 Viking 集合。
- NG-002: 不让 KnowledgeBase addDoc(tosUrl) 支持 API Key-only；该动作仍需有效 AK/SK。
- NG-003: 不改变 MODEL_AGENT_API_KEY 的 Ark 模型鉴权语义，也不允许其作为 Viking API Key 的回退来源。
- NG-004: 不修改 WebSearch、TLS Trace、Mem0、OpenSearch、OpenViking 等其他组件的鉴权行为。
- NG-005: 不新增控制台、UI、服务端接口、数据库结构或数据迁移。
- NG-006: 不在本阶段指定 Java 实现类、HTTP 请求构造方式或第三方客户端选型。

## 3. Glossary

| 术语 | 定义 |
|---|---|
| 数据面 | 本需求中特指 KnowledgeBase 查询，以及 Memory 记忆添加和记忆查询。 |
| 管理面 | 集合存在性查询、初始化预检查、集合创建/更新/删除等资源管理动作。 |
| API Key-only | 配置了对应 Viking API Key，但没有同时配置有效 AK/SK。 |
| 有效 API Key | 去除首尾空白后非空，且不区分大小写不等于字符串 none 或 null 的 API Key。Java null 同样视为未配置。 |
| 显式配置 | Java 调用方通过 SDK 公共配置入口为当前 Viking 能力直接提供的配置值。 |

## 4. Functional Requirements

### REQ-001: 独立配置并解析两类 Viking API Key

- User Story: As a Java SDK 使用者, I want 为 KnowledgeBase 与 Memory 分别配置 API Key, so that 不同 Viking 数据面资源可以使用各自凭证。
- Priority: P2
- Description: SDK 应为 KnowledgeBase 和 Memory 提供相互独立的显式 API Key 配置，并分别支持对应环境变量。配置解析不得混用 Ark 模型 API Key。

Acceptance Requirements (EARS):

- REQ-001.1: The SDK shall 为 Viking KnowledgeBase 提供显式 apiKey 配置，并以 DATABASE_VIKING_API_KEY 作为对应环境变量来源。
- REQ-001.2: The SDK shall 为 Viking Memory 提供显式 apiKey 配置，并以 DATABASE_VIKINGMEM_API_KEY 作为对应环境变量来源。
- REQ-001.3: When 同一能力同时存在有效显式 API Key 与有效环境变量 API Key, the SDK shall 选择显式 API Key。
- REQ-001.4: When 显式 API Key 未配置或无效且对应环境变量 API Key 有效, the SDK shall 选择环境变量 API Key。
- REQ-001.5: If 显式值或环境变量值为 Java null、空字符串、全空白，或去除首尾空白后不区分大小写等于 none / null, then the SDK shall 将该值视为未配置并继续解析下一优先级。
- REQ-001.6: If 当前能力未解析到有效 API Key, then the SDK shall 沿用当前 VOLCENGINE_ACCESS_KEY / VOLCENGINE_SECRET_KEY 鉴权路径。
- REQ-001.7: The SDK shall 不读取 MODEL_AGENT_API_KEY 作为 Viking KnowledgeBase 或 Viking Memory 凭证。

Gherkin:

~~~gherkin
Scenario: 显式 KnowledgeBase API Key 覆盖环境变量
  Given 调用方为 KnowledgeBase 显式配置了有效 API Key
  And 运行环境也配置了不同的 DATABASE_VIKING_API_KEY
  When 调用方查询已有 KnowledgeBase 集合
  Then SDK 使用显式 API Key 访问数据面
  And 不使用环境变量中的 API Key

Scenario: 无效显式 Memory API Key 回退到环境变量
  Given 调用方为 Memory 显式配置的 API Key 是空白字符串
  And 运行环境配置了有效的 DATABASE_VIKINGMEM_API_KEY
  When 调用方添加或查询记忆
  Then SDK 使用环境变量中的 Memory API Key
~~~

### REQ-002: KnowledgeBase 查询支持 API Key 数据面鉴权

- User Story: As a Java SDK 使用者, I want 用 API Key 查询已有 Viking KnowledgeBase 集合, so that 仅使用查询能力时无需提供 AK/SK。
- Priority: P2
- Description: 配置有效 KnowledgeBase API Key 后，所有经 KnowledgeBase Viking backend 发起的知识查询均应使用该 API Key；公开查询结果契约与现有 AK/SK 路径保持一致。

Acceptance Requirements (EARS):

- REQ-002.1: When 已解析到有效 KnowledgeBase API Key 且调用方查询已有集合, the SDK shall 使用 API Key 完成该次数据面鉴权。
- REQ-002.2: When API Key 与 AK/SK 同时有效, the SDK shall 对 KnowledgeBase 查询优先使用 API Key。
- REQ-002.3: When 查询成功, the SDK shall 保持现有知识条目内容、元数据和空结果结构不因鉴权方式而改变。
- REQ-002.4: If API Key 无效、过期或权限不足, then the SDK shall 通过现有 SDK 错误/异常契约暴露可定位的鉴权失败，并使其可与查询成功但结果为空区分。
- REQ-002.5: If 目标集合不存在, then the SDK shall 暴露可定位的资源不存在失败，不得将其报告为查询成功。

Gherkin:

~~~gherkin
Scenario: 仅用 API Key 查询已有知识库集合
  Given 已有可访问的 Viking KnowledgeBase 集合
  And 调用方只配置了有效 DATABASE_VIKING_API_KEY
  When 调用方执行知识查询
  Then 查询使用 API Key 鉴权
  And 返回结构与 AK/SK 查询路径一致
~~~

### REQ-003: Viking Memory 添加与查询支持 API Key 数据面鉴权

- User Story: As a Java SDK 使用者, I want 用 API Key 添加和查询长期记忆, so that Memory 数据面调用无需提供 AK/SK。
- Priority: P2
- Description: 配置有效 Memory API Key 后，记忆添加与记忆查询都应使用该 API Key；消息筛选、用户维度、查询数量和返回结构继续遵循现有 Memory 契约。

Acceptance Requirements (EARS):

- REQ-003.1: When 已解析到有效 Memory API Key 且调用方保存包含有效消息的会话, the SDK shall 使用 API Key 完成记忆添加。
- REQ-003.2: When 已解析到有效 Memory API Key 且调用方查询用户记忆, the SDK shall 使用 API Key 完成记忆查询。
- REQ-003.3: When API Key 与 AK/SK 同时有效, the SDK shall 对 Memory 添加和查询优先使用 API Key。
- REQ-003.4: When 数据面调用成功, the SDK shall 保持现有记忆添加结果和查询返回结构不因鉴权方式而改变。
- REQ-003.5: If API Key 无效、过期、权限不足或集合不存在, then the SDK shall 通过现有 SDK 错误/异常契约暴露可定位失败，并使查询失败可与合法空结果区分。

Gherkin:

~~~gherkin
Scenario: 仅用 API Key 添加并查询记忆
  Given 已有可访问的 Viking Memory 集合
  And 调用方只配置了有效 DATABASE_VIKINGMEM_API_KEY
  When 调用方添加有效会话记忆并查询该用户记忆
  Then 两次数据面调用都使用 Memory API Key 鉴权
  And 调用结果遵循现有 Memory 公共契约
~~~

### REQ-004: 区分数据面与管理面凭证边界

- User Story: As a Java SDK 使用者, I want SDK 在 API Key-only 场景避免执行管理面动作, so that 可以直接使用已有集合且不会因缺少 AK/SK 在初始化阶段失败。
- Priority: P2
- Description: API Key 只授权本需求定义的数据面。初始化、集合管理和 KnowledgeBase 文档导入必须遵循明确边界。

Acceptance Requirements (EARS):

- REQ-004.1: When 对应 API Key 有效且没有有效 AK/SK, the SDK shall 完成基础配置与名称校验，但跳过需要管理面凭证的集合存在性检查与自动创建。
- REQ-004.2: When API Key 与 AK/SK 同时有效, the SDK shall 对集合检查、创建等管理面动作使用 AK/SK，不得使用 API Key 替代。
- REQ-004.3: When 未配置有效 API Key 且 AK/SK 有效, the SDK shall 保持现有初始化阶段集合检查与必要时创建集合的行为。
- REQ-004.4: The SDK shall 不把 KnowledgeBase addDoc(tosUrl) 纳入 API Key-only 支持范围；该动作仅在有效 AK/SK 管理面链路下属于受支持行为。
- REQ-004.5: If API Key-only 调用方访问不存在的集合, then the SDK shall 暴露数据面返回的可定位失败，不得在初始化阶段隐式创建集合。

Gherkin:

~~~gherkin
Scenario: API Key-only 初始化使用已有集合
  Given 调用方配置了有效 Viking API Key
  And 没有配置有效 AK/SK
  And 目标集合已经存在
  When 调用方初始化相应 Viking 能力
  Then SDK 跳过集合存在性检查与自动创建
  And 后续受支持的数据面调用可以继续执行
~~~

### REQ-005: 保持现有 Java SDK 调用兼容

- User Story: As an existing Java SDK 使用者, I want 升级后继续使用原有构造入口和 AK/SK 配置, so that 无需修改既有业务代码。
- Priority: P2
- Description: API Key 能力是增量能力，不得破坏现有公开构造入口、方法签名、默认参数和 AK/SK 行为。

Acceptance Requirements (EARS):

- REQ-005.1: The SDK shall 保留现有公开构造入口、方法签名和返回类型，使未使用 API Key 的调用代码保持兼容。
- REQ-005.2: When 调用方仅配置现有 AK/SK, the SDK shall 保持当前 KnowledgeBase 与 Memory 的初始化、管理面和数据面行为。
- REQ-005.3: The SDK shall 通过现有 config / builder 使用模式提供显式 API Key 能力，并避免要求现有调用方修改主调用流程。
- REQ-005.4: The deprecated VikingKnowledgebaseService shall 至少通过环境变量继承同等的 KnowledgeBase API Key 数据面能力；需要显式 API Key 的调用方应有文档化的 KnowledgeBase 迁移入口。
- REQ-005.5: The SDK shall 保持现有默认 region、集合名称校验、Memory event type、查询参数默认值和成功返回结构，除非本 Spec 有明确变更。

### REQ-006: 提供安全且可理解的配置与失败反馈

- User Story: As a Java SDK 使用者, I want 清晰配置并定位鉴权问题, so that 可以安全完成接入和排障。
- Priority: P2
- Description: SDK 文档与运行反馈必须区分两类 Viking API Key、Ark 模型 API Key 与 AK/SK，并保护所有凭证。

Acceptance Requirements (EARS):

- REQ-006.1: The SDK documentation shall 同时说明显式 API Key、两个环境变量、优先级、空值规则、AK/SK 回退和管理面边界。
- REQ-006.2: The SDK documentation shall 明确 MODEL_AGENT_API_KEY 仅用于 Ark 模型，不属于 Viking 数据面 API Key。
- REQ-006.3: The SDK documentation shall 明确 API Key-only 要求目标集合已存在，且 addDoc(tosUrl) 仍需要 AK/SK。
- REQ-006.4: The SDK shall 在日志、异常、测试输出和文档示例中不输出真实 API Key、AK/SK、token、Authorization 值或完整连接串。
- REQ-006.5: When 数据面调用失败, the SDK shall 保留足以定位操作类型、目标资源和下游错误类别的非敏感上下文，不得把凭证值拼入错误信息。

## 5. SDK Contract

本节定义调用方可依赖的公共契约，不规定内部实现方式。

| 能力 | 显式配置语义 | 环境变量 | 数据面鉴权 | 管理面鉴权 |
|---|---|---|---|---|
| Viking KnowledgeBase | 可选 String 类型的独立 apiKey；默认未配置；按 §3 清洗无效值 | DATABASE_VIKING_API_KEY | 查询优先使用 API Key | 集合管理与 addDoc(tosUrl) 使用 AK/SK |
| Viking Memory | 可选 String 类型的独立 apiKey；默认未配置；按 §3 清洗无效值 | DATABASE_VIKINGMEM_API_KEY | 记忆添加、查询优先使用 API Key | 集合管理使用 AK/SK |

统一优先级：

1. 当前能力的有效显式 API Key。
2. 当前能力的有效环境变量 API Key。
3. 现有 AK/SK 路径。

兼容性约束：

- API Key 配置是可选增量字段；未配置时不得改变现有行为。
- KnowledgeBase 与 Memory 的 API Key 不互相回退或复用。
- SDK 不新增或修改 Viking 服务端 HTTP/OpenAPI 契约，只消费服务端已有鉴权能力。
- API Key 鉴权失败、权限不足和资源不存在必须可与成功空结果区分；具体 Java 异常类型由后续设计在现有错误模型内确定。

## 6. Backend/Data Boundary

### 6.1 行为边界

| 场景 | 有效 API Key | 有效 AK/SK | 预期行为 |
|---|---:|---:|---|
| KnowledgeBase 查询 | 是 | 否或是 | 使用 KnowledgeBase API Key |
| Memory 添加/查询 | 是 | 否或是 | 使用 Memory API Key |
| 初始化集合管理预检查 | 是 | 否 | 跳过，不自动创建集合 |
| 初始化集合管理预检查 | 任意 | 是 | 使用 AK/SK 保持现有检查/创建行为 |
| KnowledgeBase addDoc(tosUrl) | 是 | 否 | 不属于受支持的 API Key-only 场景 |
| KnowledgeBase addDoc(tosUrl) | 任意 | 是 | 使用 AK/SK 保持现有行为 |
| 任一 Viking 数据面调用 | 否 | 是 | 使用 AK/SK 保持现有行为 |

### 6.2 数据影响

- 本需求不新增或修改 veadk-java 自身的数据模型、持久化结构或迁移流程。
- API Key 与 AK/SK 路径应访问同一调用方指定的 Viking 集合，并保持业务数据结构一致。
- SDK 不存储 API Key；凭证生命周期和轮换由调用方运行环境负责。

### 6.3 异常边界

- 无效或无权限 API Key：暴露可定位的鉴权/授权失败，不回退到 AK/SK 重试同一数据面请求，避免掩盖显式配置错误。
- 目标集合不存在：API Key-only 初始化不创建集合，由首次数据面调用暴露资源不存在失败。
- 网络、服务或响应格式异常：沿用现有 SDK 错误传播约定，同时满足 REQ-006 的凭证保护要求。
- 查询成功但无匹配结果：继续返回现有空结果结构，不得误报为鉴权失败。

## 7. UX Design

本需求不涉及前端 UX、页面、交互或视觉变更。

## 8. Metrics

本需求不涉及新增或变更埋点、Dashboard 或指标口径；验收以 SDK 自动化测试和受控的集成验证为准。

## 9. NFR / DFX

### NFR-001: 向后兼容

- 要求: 保留现有公开入口与 AK/SK 行为；API Key 为可选增量配置，不改变已有成功返回结构和默认行为。
- 验收方法: 编译并运行现有 Viking KnowledgeBase / Memory 测试；增加仅 AK/SK 的回归场景，并验证现有调用代码无需修改。

### NFR-002: 凭证安全

- 要求: API Key、AK/SK、token、Authorization 值和完整连接串不得出现在日志、异常、测试报告或文档示例；错误仍保留非敏感定位上下文。
- 验收方法: 使用唯一假凭证覆盖成功和失败路径，检索测试输出与日志，断言凭证值和完整鉴权头均不存在，同时确认操作类型与错误类别可定位。
- 规范依据: 《ArkClaw/Agentkit观测数据脱敏手册》，revision 204，要求 API Key、AK/SK、Token 与 Authorization 等敏感数据不得进入观测输出；该手册的 Friday 拦截器实现细节不适用于本 Java SDK，但敏感数据保护原则适用。

### NFR-003: 配置确定性

- 要求: 相同显式参数与环境变量组合必须得到稳定、可预测的凭证选择；KnowledgeBase 与 Memory 配置相互隔离。
- 验收方法: 覆盖显式优先、环境变量回退、无效值清洗、AK/SK 回退、双凭证数据面/管理面分流和两类 API Key 互不复用的组合测试。

### NFR-004: 错误可诊断性

- 要求: API Key 鉴权失败、权限不足、资源不存在、网络/服务异常与合法空结果可区分，且不得通过静默回退掩盖显式 API Key 错误。
- 验收方法: 对每类失败注入受控响应，验证公开错误/异常契约、非敏感定位信息和空结果行为。

### NFR-005: 发布与回滚

- 要求: 本变更随 SDK 新版本发布，不做数据迁移；若需回滚，应可回退 SDK 版本并继续使用 AK/SK，调用方数据无需转换。
- 验收方法: 发布前完成仅 AK/SK 回归与 API Key 新路径验证；回滚演练验证原 AK/SK 示例仍可工作。

### 差异说明

- 产品形态: 仅 Java SDK；Python 仓库只作为行为参考，不在本需求中修改。
- 站点/地域: 不新增 BytePlus 或新地域支持，沿用 Java SDK 当前 Volcengine Viking 范围和默认 region。
- 环境: 本地、测试和生产环境使用相同配置优先级；真实凭证不得进入仓库或测试产物。
- 版本: 新 SDK 版本新增 API Key 能力，旧 AK/SK 调用保持兼容；不改变 Viking 服务端版本契约。

## 10. Traceability

| REQ / NFR | 业务/契约章节 | QA focus | 主要证据 |
|---|---|---|---|
| REQ-001 | §5 SDK Contract | 显式优先、环境变量回退、空值清洗、AK/SK 回退、凭证隔离 | 已确认需求澄清；veadk-python@31d2c67 两类 API Key 配置 |
| REQ-002 | §5、§6.1、§6.3 | KnowledgeBase API Key-only、双凭证优先级、空结果与失败区分 | veadk-java@8a9d00a KnowledgeBase 基线；Python 查询参考 |
| REQ-003 | §5、§6.1、§6.3 | Memory 添加/查询 API Key-only、双凭证优先级、返回兼容 | veadk-java@8a9d00a Memory 基线；Python Memory 参考 |
| REQ-004 | §6.1、§6.2 | 跳过管理预检、AK/SK 管理面、addDoc 边界、集合不存在 | 已确认需求澄清；Java 初始化基线；Python API Key-only 行为 |
| REQ-005 | §5 | 旧构造入口、仅 AK/SK 回归、deprecated 入口迁移 | Java SDK 当前公开入口与测试基线 |
| REQ-006 | §9 NFR-002、NFR-004 | README / README_zh、一致错误语义、假凭证泄漏扫描 | 已确认需求澄清；观测数据脱敏规范 revision 204 |
| NFR-001 | §9 | 编译与既有测试回归 | Java SDK 当前公开契约 |
| NFR-002 | §9 | 日志、异常、测试与示例凭证扫描 | 观测数据脱敏规范 revision 204 |
| NFR-003 | §9 | 配置组合测试 | 用户明确的配置优先级；Python 参考行为 |
| NFR-004 | §6.3、§9 | 失败注入与空结果区分 | 已确认需求澄清 |
| NFR-005 | §9 | 新旧鉴权路径验证与 SDK 版本回退 | 无 Schema 变化；现有 AK/SK 路径保留 |

## 11. Evidence References

- Meego story 7379276505：原始需求、P2 优先级、Java SDK 模块范围、安全技术评审标记与当前节点状态，读取时间 2026-09-28。
- 已确认需求澄清产物：veadk-java-viking-apikey-requirement-clarification-confirmed，artifact checksum sha256:36afeab472fdda4b8127a746ebfad6dadd05be11440389b6c389e1a967d764b9。
- veadk-java@8a9d00ad183ceb15751cd9529f152a3022a2707d：KnowledgeBase / Viking Memory 公共入口、AK/SK 配置、集合预检查和现有测试基线。
- [veadk-python@31d2c67](https://github.com/volcengine/veadk-python/tree/31d2c67be6fb9bd7f3139de42d2615c2c4a73e6f)：Viking KnowledgeBase / Memory API Key 配置、空值清洗、数据面鉴权和 API Key-only 初始化参考。
- 《火山引擎ArkClaw OpenAPI设计规范》，[原文](https://bytedance.larkoffice.com/wiki/R4fIweN5gizXUQkmWCicGQDBnqf)，revision 173：兼容性、鉴权、默认值/空值与错误语义规范；本需求不修改服务端 OpenAPI，采用其契约审查原则。
- 《ArkClaw/Agentkit观测数据脱敏手册》，[原文](https://bytedance.larkoffice.com/docx/VYRSdHS4UoAFbdxUnkqceXLtnWg)，revision 204：API Key、AK/SK、Token 与 Authorization 敏感信息保护要求。
