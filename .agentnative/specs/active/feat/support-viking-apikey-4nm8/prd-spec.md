# Review Summary

- 本次需求: 为 veadk-java 的 Viking Knowledgebase 查询与 Viking Memory 记忆写入、检索增加 API Key 鉴权，同时保持既有 AK/SK 能力兼容。
- 需求类型: Java SDK 公共配置契约、后端集成行为、鉴权与安全、兼容性。
- 变更面: Functional Requirements、Backend/Data、API Design、NFR / DFX、文档与测试验收；不涉及: 前端 UX、数据模型/Schema、Metrics/Dashboard、非 Viking 组件；待确认: 无阻塞项。
- 变更面判断依据: 原始需求与已确认的需求澄清文档；veadk-java 当前 Viking backend、memory service、wrapper、环境变量和 README；veadk-python 固定提交 `31d2c67be6fb9bd7f3139de42d2615c2c4a73e6f` 的实现、测试与用户文档。
- 差异判断: 产品形态仅涉及 Java SDK；站点仅新增 Volcengine 支持，BytePlus 保持现状；显式参数与环境变量两种配置形态遵循同一优先级；新旧 SDK 版本存在新增配置能力差异，但既有 AK/SK 调用保持兼容。依据: 需求澄清结论、仓库当前公共构造/配置入口与 Python 参考实现。
- 本次变更关键信息:
  - 本轮返修统一凭证缺失语义：对应 API Key 本地未配置有效值时，一对有效 AK/SK 继续承接数据面请求；仅在 API Key 本地未配置有效值且不存在一对有效 AK/SK 时报告凭证缺失。
  - Knowledgebase 的 API Key 范围固定为搜索已有 collection；文档添加、collection 管理等操作不扩展 API Key 权限。
  - Memory 的 API Key 范围固定为已有 Java 公共能力中的记忆添加与检索；API key-only 初始化不得执行 collection 管理预检查。
  - `DATABASE_VIKING_API_KEY` 与 `DATABASE_VIKINGMEM_API_KEY` 分别独立生效；有效显式参数优先于环境变量，未配置 API Key 时回到既有 AK/SK 链路。
  - API Key、AK/SK 与 Authorization 信息不得进入日志、异常、测试输出或文档示例。
- Review 重点:
  - 核对 API Key 数据面范围是否严格等同于固定 Python 基线，尤其是 Knowledgebase `addDoc` 不纳入、Memory collection 管理不纳入。
  - 核对 REQ-005.1 已与 REQ-004 及鉴权选择矩阵一致：API Key 本地未配置有效值时先按 AK/SK 兼容路径处理，只有同时不存在一对有效 AK/SK 才报告凭证缺失。
  - 核对配置解析、双凭证共存、无效显式值、API key-only 初始化和服务端鉴权失败不降级语义。
  - 核对公共 SDK 向后兼容、Volcengine/BytePlus 边界和敏感信息保护。

## Scope Note

- Implemented sections: Context、Goals and Non-Goals、Glossary、Functional Requirements、Backend/Data、API Design、NFR / DFX、Documentation and Verification、Traceability。
- Not involved: 前端页面与交互、数据库或持久化 Schema、OpenAPI/RPC/Webhook/CLI、业务 Metrics/Dashboard、BytePlus 新能力、非 Viking 组件。
- Needs confirmation: 无阻塞项；具体 Java 类、构造方法重载、HTTP/SDK 适配方式和文件拆分属于后续技术设计。
- Reason: 需求和 Human 澄清已明确单仓 Java SDK 鉴权范围；Java 当前实现与固定 Python 基线足以定义可验收行为，但不应在 Spec 阶段预设实现结构。

# Spec: veadk-java Viking 数据面支持 API Key 请求

## 1. Context

veadk-java 当前通过 `VOLCENGINE_ACCESS_KEY` 与 `VOLCENGINE_SECRET_KEY` 为 Viking Knowledgebase 和 Viking Memory 请求提供 AK/SK 鉴权。用户若只有某个已有 Viking collection 的 API Key，无法使用 Java SDK 完成知识库查询、记忆添加或记忆检索。

veadk-python 已提供两条独立的 API Key 配置与数据面访问路径。本需求让 Java SDK 用户获得一致的核心能力，并继续支持原有 AK/SK 用户。目标用户包括直接调用 KnowledgeBase / VikingMemoryService 的 Java 开发者，以及通过 VeADK Runner 使用这些能力的应用开发者。

本 Spec 以 veadk-java 当前分支基线 `8a9d00ad183ceb15751cd9529f152a3022a2707d` 和 veadk-python 固定参考提交 `31d2c67be6fb9bd7f3139de42d2615c2c4a73e6f` 为行为基线；未来 Python 行为变化不会自动扩大本次范围。

### 1.1 Evidence

| ID | 来源 | 支撑结论 |
| --- | --- | --- |
| EVD-001 | 已确认的《Viking 支持 API Key 请求 - 需求澄清文档 v2》 | API Key 范围与 Python 对齐；显式参数优先；环境变量名固定；API key-only 管理行为对齐；仅覆盖 Volcengine。 |
| EVD-002 | veadk-java `VikingKnowledgebaseBackend`、`VikingKnowledgebaseConfig`、`VikingMemoryService` | Java 当前仅从 AK/SK 构造 Viking 客户端，并在初始化时检查或创建 collection。 |
| EVD-003 | veadk-java `KnowledgeBase`、`BaseMemoryService` 与对应 wrapper | 当前面向用户的相关数据面能力为 Knowledgebase 搜索、Memory 添加会话与检索；Knowledgebase 另有 `addDoc`。 |
| EVD-004 | [veadk-python](https://github.com/volcengine/veadk-python) 固定参考提交 `31d2c67be6fb9bd7f3139de42d2615c2c4a73e6f` 中的 Viking backend、测试和文档 | Knowledgebase API Key 用于搜索已有 collection；Memory API Key 用于已有 collection 的记忆操作；collection 管理仍使用 AK/SK/IAM；空 API Key 会回退环境变量。 |
| EVD-005 | veadk-java `README.md` / `README_zh.md` | 当前用户文档仅声明 Viking 使用 AK/SK，需要补充两种 API Key 配置方式及边界。 |
| EVD-006 | 《ArkClaw/Agentkit 管控日志打印规范》([原文](https://bytedance.larkoffice.com/docx/ETK2dt4oRoeuvkxOFgvclKnWnYb)，revision 20)、《ArkClaw/Agentkit观测数据脱敏手册》([原文](https://bytedance.larkoffice.com/docx/VYRSdHS4UoAFbdxUnkqceXLtnWg)，revision 204) 的离线 Source Card | Token、Secret、Authorization 和 API Key 不得以明文进入日志与观测数据；飞书原文因当前账号无权限，精确时效性规则需后续回源核验。 |

## 2. Goals and Non-Goals

### 2.1 Goals

- GOAL-001: Java SDK 用户可使用 API Key 搜索已有 Viking Knowledgebase collection，返回结构与既有 AK/SK 路径一致。
- GOAL-002: Java SDK 用户可使用 API Key 向已有 Viking Memory collection 添加记忆并检索记忆，返回结构与既有 AK/SK 路径一致。
- GOAL-003: 用户可按实例显式配置 API Key，也可通过约定环境变量配置；来源选择确定、可测试。
- GOAL-004: 仅配置 API Key 的用户可直接使用本次数据面能力，不被 AK/SK 专属的 collection 管理预检查阻塞。
- GOAL-005: 未配置 API Key 的已有用户无需修改代码或 AK/SK 配置即可继续使用原有能力。

### 2.2 Non-Goals

- NG-001: 不替换、下线或改变现有 AK/SK 鉴权能力。
- NG-002: 不让 API Key 获得 collection 创建、查询状态、列举、更新或删除等管理能力。
- NG-003: Knowledgebase 的文档添加、文档/切片管理及依赖 TOS 上传的能力不纳入 API Key 范围；`addDoc` 继续沿用既有 AK/SK 语义。
- NG-004: 不为 Java SDK 新增 Python 中存在、但 Java 当前没有公开的 Viking 业务操作，例如单独的用户画像读取 API。
- NG-005: 不修改 Knowledgebase 或 Memory 的业务数据结构、召回排序、`topK`、rerank、chunk diffusion、memory type 等非鉴权语义。
- NG-006: 不扩大 BytePlus、Ark 模型、OpenSearch、Mem0、WebSearch、Trace 或其他非 Viking 能力。
- NG-007: 不新增前端、数据库 Schema、OpenAPI/RPC/Webhook/CLI 或业务指标。

## 3. Glossary

| 术语 | 定义 |
| --- | --- |
| API Key | Viking 专用访问凭证；Knowledgebase 与 Memory 使用彼此独立的配置项。 |
| AK/SK | 火山引擎 Access Key / Secret Key；保留为既有鉴权方式，并继续承担本次范围外的管理操作。 |
| 数据面 | 本需求中仅指 Knowledgebase 搜索，以及 Memory 记忆添加和检索。 |
| 管理操作 | collection 的检查、创建、列举、更新、删除，以及 Knowledgebase 文档/切片管理等不属于本次 API Key 范围的操作。 |
| API key-only | 存在有效 Viking API Key，但不存在一对有效 AK/SK 的配置状态。 |
| 有效 API Key | 去除首尾空白后非空，且不等于不区分大小写的 `none` 或 `null` 的值。有效性这里只描述本地配置判定，不代表服务端鉴权一定成功。 |

## 4. Functional Requirements

### REQ-001: Knowledgebase 搜索支持 API Key

- User Story: As a Java SDK 用户, I want 使用 Viking Knowledgebase API Key 搜索已有 collection, so that 我无需 AK/SK 即可完成只读检索。
- Priority: P0
- Description: 当 Knowledgebase 存在有效 API Key 时，搜索已有 collection 应使用该 API Key；请求应保留现有 collection 名称、query、topK、过滤和后处理语义，并支持服务端识别目标资源所需的 project 与可选 resource ID 语义。返回的 `KnowledgebaseEntry` 内容、metadata 和无结果语义不得因鉴权方式变化。

Acceptance Requirements (EARS):

- REQ-001.1: When 用户对已有 Viking Knowledgebase collection 发起搜索且存在有效 API Key, the Java SDK shall 使用 API Key 完成鉴权，而不要求同时存在 AK/SK。
- REQ-001.2: When API Key 搜索成功, the Java SDK shall 返回与 AK/SK 搜索相同的公共结果类型和字段语义。
- REQ-001.3: When API Key 搜索没有命中结果, the Java SDK shall 返回现有空结果语义，而不是将空结果视为鉴权失败。
- REQ-001.4: If 用户调用 Knowledgebase 文档添加或 collection 管理操作, then the Java SDK shall 不把 API Key 当作这些操作的管理凭证，并保持既有 AK/SK 要求。

Gherkin:

```gherkin
Scenario: 仅使用 API Key 搜索已有知识库
  Given 用户已配置有效的 Knowledgebase API Key 且目标 collection 已存在
  And 用户未配置 AK/SK
  When 用户通过 Java SDK 搜索该 collection
  Then SDK 使用 API Key 发起搜索
  And 返回结果的公共类型与 AK/SK 路径一致
  And 初始化过程不执行需要 AK/SK 的 collection 管理预检查
```

### REQ-002: Memory 记忆添加与检索支持 API Key

- User Story: As a Java SDK 用户, I want 使用 Viking Memory API Key 写入并检索已有 collection 中的记忆, so that 运行时记忆能力不依赖 AK/SK。
- Priority: P0
- Description: 当 Memory 存在有效 API Key 时，现有 `addSessionToMemory` 和 `searchMemory` 数据面行为使用 API Key。消息筛选、metadata、用户过滤、memory type、topK、异步完成/异常传播和返回类型保持现有业务语义。

Acceptance Requirements (EARS):

- REQ-002.1: When 用户向已有 Viking Memory collection 添加有效会话记忆且存在有效 API Key, the Java SDK shall 使用 API Key 完成该数据面请求。
- REQ-002.2: When 用户检索已有 Viking Memory collection 且存在有效 API Key, the Java SDK shall 使用 API Key 完成该数据面请求并返回现有公共响应类型。
- REQ-002.3: When 会话中没有符合现有规则的可写入消息, the Java SDK shall 保持现有无请求并正常完成的行为。
- REQ-002.4: If 用户只有 API Key, then the Java SDK shall 不执行需要 AK/SK 的 collection 存在性检查或自动创建。

Gherkin:

```gherkin
Scenario: 仅使用 API Key 添加并检索记忆
  Given 用户已配置有效的 Memory API Key 且目标 collection 已存在
  And 用户未配置 AK/SK
  When 用户添加包含有效用户消息的 session 并随后检索记忆
  Then 两个数据面请求均使用 API Key
  And 添加完成及检索结果保持现有 Java 公共接口语义
  And 初始化过程不检查或创建 collection
```

### REQ-003: 配置来源、空值与优先级

- User Story: As a Java SDK 用户, I want 在实例级参数和环境变量之间获得确定的选择规则, so that 多实例与部署配置不会互相覆盖或产生隐式降级。
- Priority: P0
- Description: Knowledgebase 与 Memory 必须各自提供显式 API Key 配置入口，并分别读取 `DATABASE_VIKING_API_KEY` 与 `DATABASE_VIKINGMEM_API_KEY`。两者互不回退、互不复用，也不复用 `MODEL_AGENT_API_KEY`。

Acceptance Requirements (EARS):

- REQ-003.1: When 有效显式 API Key 与对应环境变量同时存在, the Java SDK shall 使用显式值。
- REQ-003.2: When 显式值为 `null`、空字符串、纯空白或不区分大小写的 `none` / `null`, the Java SDK shall 将其视为未配置并读取对应环境变量。
- REQ-003.3: When 对应环境变量也是无效值, the Java SDK shall 将 API Key 视为未配置并进入 REQ-004 的 AK/SK 兼容路径。
- REQ-003.4: When Knowledgebase 与 Memory 配置不同 API Key, the Java SDK shall 在各自能力中独立使用对应值。
- REQ-003.5: If 已选择的非空 API Key 被服务端判定为无效、过期或无权限, then the Java SDK shall 暴露该鉴权失败，不得在同一次数据面调用中静默改用环境变量或 AK/SK。

Gherkin:

```gherkin
Scenario: 显式 API Key 覆盖环境变量
  Given 对应环境变量为一个有效 API Key
  And 当前 SDK 实例显式配置了另一个有效 API Key
  When 用户调用对应 Viking 数据面能力
  Then SDK 使用显式 API Key

Scenario: 空显式值回退环境变量
  Given 对应环境变量为一个有效 API Key
  And 当前 SDK 实例显式值为纯空白
  When 用户调用对应 Viking 数据面能力
  Then SDK 使用环境变量中的 API Key
```

### REQ-004: AK/SK 向后兼容与双凭证共存

- User Story: As an existing Java SDK 用户, I want 升级后原有 AK/SK 配置继续工作, so that 新鉴权能力不会造成迁移中断。
- Priority: P0
- Description: API Key 是可选的增量配置。没有有效 API Key 时，Viking Knowledgebase 与 Viking Memory 继续使用既有 `VOLCENGINE_ACCESS_KEY` / `VOLCENGINE_SECRET_KEY` 路径。API Key 与 AK/SK 同时存在时，数据面选择 API Key，管理操作仍使用 AK/SK。

Acceptance Requirements (EARS):

- REQ-004.1: When 未配置有效 API Key 且存在有效 AK/SK, the Java SDK shall 保持现有 Viking 数据面与管理行为。
- REQ-004.2: When 有效 API Key 与 AK/SK 同时存在, the Java SDK shall 对本次范围内数据面操作使用 API Key，并允许既有管理操作继续使用 AK/SK。
- REQ-004.3: When 既无有效 API Key 也无一对有效 AK/SK, the Java SDK shall 在发出目标请求前或最接近鉴权边界处给出明确的凭证缺失错误。
- REQ-004.4: The Java SDK shall 保持现有无需新参数的公共构造方式和调用代码可用，不要求 AK/SK 用户迁移到 API Key。

Gherkin:

```gherkin
Scenario: API Key 未配置时回退有效 AK/SK
  Given 对应能力未配置有效 API Key
  And 用户已配置一对有效 AK/SK
  When 用户调用对应 Viking 数据面能力
  Then SDK 使用 AK/SK 并保持现有行为
  And 不报告凭证缺失

Scenario: API Key 本地未配置有效值且无有效 AK/SK 时报告凭证缺失
  Given 对应能力未配置有效 API Key
  And 用户未配置一对有效 AK/SK
  When 用户调用对应 Viking 数据面能力
  Then SDK 报告对应能力的凭证缺失
  And 错误不包含任何凭证值
```

### REQ-005: 可诊断且不泄密的失败行为

- User Story: As a Java SDK 用户, I want 区分配置缺失、鉴权/权限失败、资源问题和空查询结果, so that 我可以安全定位问题。
- Priority: P0
- Description: API Key 路径应保持现有 Java 异常风格，但不得把服务端失败吞并为空查询结果或普通成功。错误与日志可包含操作、鉴权模式、非敏感资源标识和服务端非敏感错误信息，不得包含凭证值或完整 Authorization 信息。

Acceptance Requirements (EARS):

- REQ-005.1: When 对应能力既无有效 API Key（包括缺失或本地判定为未配置）也无一对有效 AK/SK, the Java SDK shall 给出能识别 Knowledgebase 或 Memory 所需配置来源的凭证缺失错误，且不包含任何凭证值；存在一对有效 AK/SK 时不得因 API Key 缺失或本地判定无效而报告凭证缺失。
- REQ-005.2: If 已选择的非空 API Key 被服务端判定为无效、过期或权限不足, then the Java SDK shall 向调用方暴露可识别的鉴权或权限失败，不得返回与“无结果”相同的成功响应，也不得静默改用 AK/SK。
- REQ-005.3: If 目标已有 collection 不存在或不可访问, then the Java SDK shall 暴露资源或权限失败，不得在 API key-only 模式下尝试自动创建 collection。
- REQ-005.4: If 网络、超时、服务端或响应解析异常发生, then the Java SDK shall 按现有公共异常边界向调用方传播可诊断失败。
- REQ-005.5: The Java SDK shall 不在任何日志级别、异常消息、测试失败输出或示例中输出 API Key、AK/SK、Token、Cookie 或完整 Authorization header。

### REQ-006: 用户文档与示例可发现

- User Story: As a Java SDK 用户, I want 从中英文文档了解 API Key 的配置和能力边界, so that 我可以正确选择 API Key 或 AK/SK。
- Priority: P1

Acceptance Requirements (EARS):

- REQ-006.1: The Java SDK documentation shall 说明 `DATABASE_VIKING_API_KEY` 用于搜索已有 Viking Knowledgebase collection。
- REQ-006.2: The Java SDK documentation shall 说明 `DATABASE_VIKINGMEM_API_KEY` 用于已有 Viking Memory collection 的记忆添加与检索。
- REQ-006.3: The Java SDK documentation shall 说明显式参数优先、空值回退、API key-only 不执行管理预检查，以及管理操作仍需 AK/SK。
- REQ-006.4: The Java SDK documentation and examples shall 仅使用明显的占位凭证，不提供真实或看似真实的 Secret。

## 5. Backend/Data

### 5.1 鉴权选择矩阵

| 配置状态 | Knowledgebase 搜索 | Memory 添加/检索 | collection / 文档管理 |
| --- | --- | --- | --- |
| 有效 API Key，无 AK/SK | API Key | API Key | 初始化跳过管理预检查；显式调用管理操作时报告需要 AK/SK |
| 有效 API Key，有 AK/SK | API Key | API Key | AK/SK |
| 本地未配置有效 API Key，有一对有效 AK/SK | AK/SK，保持现状 | AK/SK，保持现状 | AK/SK，保持现状 |
| 本地未配置有效 API Key，无一对有效 AK/SK | 明确凭证缺失失败 | 明确凭证缺失失败 | 明确凭证缺失失败 |

### 5.2 业务边界

- Knowledgebase API Key 请求面向已有 collection 的搜索，保持 collection name、project、query、limit、filter、rerank 与 chunk diffusion 等已有业务语义；服务端支持时可携带已配置的 resource ID。
- Memory API Key 请求面向已有 collection 的 session 添加与搜索，保持 collection、project、消息、metadata、用户过滤、memory type 与 limit 等已有业务语义。
- 仅 API Key 时跳过的是 SDK 初始化阶段的管理预检查，不是跳过 collection 存在性这一业务前提；目标资源不存在仍由数据面调用返回失败。
- 本需求不产生本地数据模型、数据库、迁移、回填、缓存或数据生命周期变化。

## 6. API Design

本节描述对 Java SDK 使用者可见的契约，不规定类拆分、构造方法重载、HTTP 客户端或依赖选型。

### 6.1 公共配置契约

| 能力 | 显式配置 | 环境变量 | 规则 |
| --- | --- | --- | --- |
| Viking Knowledgebase API Key | 可选的 `String` 类型 `apiKey` 语义配置，相关 Knowledgebase/Viking 配置支持按实例提供 | `DATABASE_VIKING_API_KEY` | 有效显式值优先；只用于 REQ-001 范围 |
| Viking Memory API Key | 可选的 `String` 类型 `apiKey` 语义配置，相关 Memory/Viking 配置支持按实例提供 | `DATABASE_VIKINGMEM_API_KEY` | 有效显式值优先；只用于 REQ-002 范围 |

- 现有不带 API Key 的公共构造和 builder 调用必须继续可用。
- 若 Knowledgebase API Key 搜索需要 project 或 resource ID，SDK 必须允许调用方表达 Python 基线支持的资源定位语义；具体 Java API 形态由技术设计确定，不能改变本 Spec 的鉴权优先级与范围。
- 两种 API Key 是不同 Viking 产品能力的凭证，不允许跨能力兜底。

### 6.2 请求与响应契约

- Knowledgebase API Key 数据面请求使用 Viking 服务认可的 Bearer 鉴权语义；Memory API Key 数据面请求使用 Viking Memory SDK/服务认可的 API Key 鉴权语义。具体传输适配由技术设计确定。
- API Key 只能发送到当前 Volcengine Viking 目标端点，不得被转发给 TOS、Ark、BytePlus 或其他下游。
- 请求字段与响应映射除鉴权和必要资源定位信息外保持现有 Java 公共语义。
- 不新增 OpenAPI、RPC、Webhook 或 CLI 契约。

### 6.3 错误契约

- 本地配置错误、服务端鉴权/权限失败、资源不存在与正常空结果必须可区分。
- 错误可复用现有 Java 异常类型或采用兼容的新增类型；不得改变上述可区分性，也不得泄露敏感值。
- API Key 请求失败后不自动切换凭证重试，以避免掩盖错误配置或执行未预期身份的请求。

## 7. NFR / DFX

### 7.1 兼容性

- 新能力为可选增量；未配置 API Key 的行为与返回类型保持兼容。
- 不删除、不改名现有公共构造、builder 方法、环境变量或 AK/SK 入口。
- Knowledgebase 与 Memory 的 API Key 配置互相隔离；非 Viking backend 不受影响。

### 7.2 安全与隐私

- API Key、AK/SK 和 Authorization 属于 Secret，不得进入日志、异常、Trace、测试快照或文档实值。
- 允许记录鉴权模式和受控的非敏感资源标识，但不得记录完整请求头或完整配置对象。
- 自动化验证必须使用假凭证，并断言正常与异常路径均不泄露该值。
- 通用规范的离线 Source Card 将禁止明文记录 Secret 作为明确安全底线；Friday 拦截器、采集端配置等服务端专属细节不适用于本 Java SDK。精确时效性规则因当前账号无法读取飞书原文而标记为 `needs_live_verification`，但不阻塞本 Spec 的凭证保护验收。

### 7.3 可靠性与性能

- API key-only 初始化不得发起 collection 管理预检查，避免无权限调用阻塞合法数据面请求，并减少一次不必要的外部调用。
- 不要求新增自动重试；请求超时、重试与并发行为不得弱于当前同类 Viking 调用，具体策略由技术设计基于现有依赖能力确认。
- 正常空结果与依赖失败必须保持语义分离，避免上游把鉴权故障误判为“没有知识/记忆”。

### 7.4 发布、回滚与地域

- 本次仅对 Volcengine 生效；BytePlus 行为与支持范围保持当前状态。
- 不涉及数据迁移。若需回滚，应可通过回退包含该能力的 SDK 版本恢复原有 AK/SK-only 行为，不产生数据清理动作。
- 不要求新增 Feature Gate；版本发布说明必须标注新增配置、支持范围与兼容边界。

## 8. Documentation and Verification

### 8.1 文档验收

- 中英文 README 或等价用户文档同步说明两项环境变量、显式配置方式、优先级、数据面范围、管理面边界与 AK/SK 兼容路径。
- 示例覆盖 Knowledgebase 与 Memory，使用占位 API Key，并明确目标 collection 需要预先存在。

### 8.2 最小验证范围

- 配置解析: 显式值、环境变量、显式优先、空白/`none`/`null` 回退、两类 Key 隔离。
- Knowledgebase: API Key 搜索、空结果、过滤/资源定位保持、`addDoc` 不使用 API Key、API key-only 跳过管理预检查。
- Memory: API Key 添加、API Key 检索、无有效消息保持现状、API key-only 跳过管理预检查。
- 兼容性: 仅 AK/SK；API Key 缺失或本地判定无效但 AK/SK 有效时回退 AK/SK；API Key 与 AK/SK 共存；既有公共构造方式。
- 失败与安全: API Key 本地未配置有效值且不存在一对有效 AK/SK 时报告凭证缺失；已选择的 API Key 被服务端判定无效、过期或无权限时暴露失败且不降级；资源不存在、网络/服务端/解析异常；日志和异常不包含假 Secret。
- 文档: 中英文说明一致，配置名与支持范围一致。

## 9. Traceability

| REQ ID | 主要证据 | 设计关注 | QA focus |
| --- | --- | --- | --- |
| REQ-001 | EVD-001、EVD-003、EVD-004 | Knowledgebase 数据面与资源定位 | 搜索、空结果、addDoc 边界 |
| REQ-002 | EVD-001、EVD-003、EVD-004 | Memory API Key client 与初始化边界 | 添加、检索、空消息、管理预检查 |
| REQ-003 | EVD-001、EVD-004 | 配置解析与凭证隔离 | 优先级、空值、双 Key、失败不降级 |
| REQ-004 | EVD-001、EVD-002 | 公共 API 与 AK/SK 兼容 | AK/SK-only、API Key 本地未配置有效值时回退、双凭证共存、两类凭证均未配置有效值 |
| REQ-005 | EVD-001、EVD-006 | 凭证缺失条件、错误映射与 Secret 防泄露 | 两类凭证均未配置有效值、服务端鉴权不降级、权限、资源、依赖失败与泄密反例 |
| REQ-006 | EVD-001、EVD-005 | 用户文档与示例 | 中英文一致性与占位凭证 |
