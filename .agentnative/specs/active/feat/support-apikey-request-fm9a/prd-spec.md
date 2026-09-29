# Review Summary

- 本次需求: 为 veadk-java 的 Viking KnowledgeBase 查询与 Viking Memory 数据面操作增加 API Key 鉴权，同时保持 AK/SK 兼容。
- 需求类型: Java SDK 公开配置契约、后端集成行为、鉴权安全、兼容性。
- 变更面: Context、Goals and Non-Goals、Glossary、Functional Requirements、SDK 配置契约、Backend/Data、文档、NFR / DFX、Traceability；不涉及: 前端 UX、服务端 OpenAPI、数据库 schema、Metrics / Dashboard；待确认: 无。
- 变更面判断依据: 已确认的需求澄清、veadk-java 当前 `KnowledgeBase` / `VikingMemoryService` / Viking wrapper 与测试、veadk-python 当前 API Key 行为及文档。
- 差异判断: 产品形态不涉及差异，仅变更 Java SDK；站点不涉及差异，本期保持 veadk-java 当前 Volcengine 能力边界；运行环境涉及显式参数与环境变量两种配置来源；版本涉及向后兼容升级。依据为任务明确限定 veadk-java、当前仓库固定 Volcengine Viking endpoint，以及 Python 参考实现的配置优先级。
- 本次变更关键信息:
  - KnowledgeBase 仅对已有 collection 的查询数据面支持 API Key；`addDoc`、TOS 上传和 collection 管理不纳入 API Key 范围。
  - Memory 对已有 collection 的记忆添加与查询数据面支持 API Key；collection 管理仍使用 AK/SK。
  - API Key 解析遵循“有效显式参数 > 有效环境变量 > AK/SK”，空白字符串及大小写不敏感的 `none`、`null` 视为未配置；一次数据面请求确定鉴权方式后不得因鉴权失败自动切换凭据。
  - 仅有 API Key 时跳过 collection 管理预检查和自动创建，调用方须预先准备 collection；任何日志、异常、测试和文档不得泄露凭据原文。
- Review 重点:
  - 确认 KnowledgeBase 与 Memory 的数据面/管理面边界和双凭据并存行为。
  - 确认现有 AK/SK 用户的构造方式、结果结构和失败语义保持兼容。
  - 确认 API Key-only 初始化不触发管理面调用，以及鉴权失败不静默降级。

## Scope Note

- Implemented sections: Context、Goals and Non-Goals、Glossary、Functional Requirements、SDK 配置契约、Backend/Data、文档要求、NFR / DFX、Traceability。
- Not involved: 前端 UX、服务端 OpenAPI、数据库 schema / 迁移、埋点、Dashboard、Feature Gate。
- Needs confirmation: 无。
- Reason: 需求与负责人回执已冻结 API Key 范围、环境变量、优先级和 API Key-only 行为；仓库证据表明本次只改变 Java SDK 配置及 Viking 客户端鉴权选择，不改变服务端协议或持久化模型。

# Spec: veadk-java Viking 数据面支持 API Key 鉴权

## 1. Context

- 需求来源: Meego story `7379276505`「【agentkit】veadk-java代码库viking支持apikey请求」。
- 现状: veadk-java 的 Viking KnowledgeBase 与 Viking Memory 均要求 `VOLCENGINE_ACCESS_KEY` / `VOLCENGINE_SECRET_KEY`，初始化时还会检查并在缺失时创建 collection。
- 问题: 仅持有 Viking 数据面 API Key 的 Java 开发者无法使用已有 collection 进行知识查询、记忆添加和记忆查询；若沿用当前初始化流程，还会错误触发需要 AK/SK 的管理面请求。
- 用户价值: Java SDK 用户可采用与 veadk-python 一致的凭据模型访问 Viking 数据面，无需为仅使用已有 collection 的运行时应用额外配置长期 AK/SK。
- 影响用户: 使用 `KnowledgeBase` Viking backend、兼容入口 `VikingKnowledgebaseService` 或 `VikingMemoryService` 的 Java 应用开发者。

### 1.1 证据与约束来源

| 来源 | 关键事实 | 用途 |
| --- | --- | --- |
| 前序已确认需求澄清 | 冻结数据面范围、环境变量名、优先级、API Key-only 管理面行为，且无剩余待确认项 | 需求与验收事实源 |
| veadk-java 当前代码与测试 | Viking wrapper 仅接受 AK/SK；KnowledgeBase 与 Memory 初始化会检查/创建 collection；现有搜索、添加和返回语义已有测试 | 兼容基线与变更面判断 |
| veadk-python 当前实现与文档 | 使用 `DATABASE_VIKING_API_KEY` / `DATABASE_VIKINGMEM_API_KEY`；显式 `api_key` 优先；空值清洗；API Key-only 跳过管理面预检查 | 行为对齐基线 |
| 《ArkClaw/Agentkit 管控日志打印规范》，[飞书原文](https://bytedance.larkoffice.com/docx/ETK2dt4oRoeuvkxOFgvclKnWnYb)，revision 20 | Secret、Token、Authorization、API Key 不得写入日志或完整请求/响应 | 安全验收依据 |
| 《ArkClaw/Agentkit观测数据脱敏手册》，[飞书原文](https://bytedance.larkoffice.com/docx/VYRSdHS4UoAFbdxUnkqceXLtnWg)，revision 204 | API Key 属于必须删除的敏感数据；测试应使用假 Secret 验证日志与观测输出 | 安全验收依据 |

## 2. Goals and Non-Goals

### 2.1 Goals

- GOAL-001: Java 开发者可使用 API Key 查询已有 Viking KnowledgeBase collection。
- GOAL-002: Java 开发者可使用 API Key 向已有 Viking Memory collection 添加记忆并查询记忆。
- GOAL-003: 配置解析稳定遵循“有效显式参数 > 有效环境变量 > AK/SK”，并让双凭据并存时的数据面与管理面职责可预测。
- GOAL-004: 现有只配置 AK/SK 的调用方式、公开入口、请求结果结构与空结果语义保持兼容。
- GOAL-005: 文档和验证材料清晰说明凭据边界，且凭据原文不进入日志、异常、测试输出或仓库内容。

### 2.2 Non-Goals

- NG-001: 不改变 Viking 服务端 API、权限模型、API Key 生命周期或授权范围。
- NG-002: 不让 API Key 承担 collection 查询存在性、创建、删除、更新、列举等管理面操作。
- NG-003: KnowledgeBase 的 `addDoc`、TOS 上传及文档/切片管理不新增 API Key 支持。
- NG-004: 不改变 OpenSearch、Mem0、WebSearch、ArkLlm、TLS Trace、Code Sandbox 等非 Viking 能力的鉴权。
- NG-005: 不在本阶段规定 Java 的具体类、方法、构造器重载、HTTP 客户端或第三方依赖选型；这些属于技术设计。
- NG-006: 不扩展 BytePlus、VeFaaS IAM 或新的地域能力；本次沿用 veadk-java 当前产品与地域边界。

## 3. Glossary

| 术语 | 定义 |
| --- | --- |
| 数据面 | 对已有 collection 中业务数据执行的操作。本需求中为 KnowledgeBase 查询，以及 Memory 记忆添加、记忆查询。 |
| 管理面 | collection 的存在性检查、创建、删除、更新、列举等资源管理操作，以及 KnowledgeBase 文档/TOS 管理。 |
| 有效 API Key | 去除首尾空白后非空，且值不等于大小写不敏感的 `none` 或 `null`。 |
| API Key-only | 存在有效 API Key，但没有同时可用的 AK 与 SK。 |
| AK/SK | `VOLCENGINE_ACCESS_KEY` 与 `VOLCENGINE_SECRET_KEY` 组成的火山引擎签名凭据。 |

## 4. Functional Requirements

### REQ-001: KnowledgeBase 查询支持 API Key

- User Story: As a 使用 Viking KnowledgeBase 的 Java 开发者, I want 使用 API Key 查询已有 collection, so that 运行时查询不再依赖 AK/SK。
- Priority: P0
- Description: Viking KnowledgeBase 的同步查询、异步查询及其 `BaseKnowledgebaseService` 兼容入口应共享同一鉴权选择；API Key 仅用于已有 collection 的查询数据面。

Acceptance Requirements (EARS):
- REQ-001.1: When 存在有效 KnowledgeBase API Key 且调用查询数据面时，veadk-java shall 使用该 API Key 发起查询。
- REQ-001.2: When API Key 查询成功时，veadk-java shall 保持现有 `KnowledgebaseEntry` 列表及 `SearchKnowledgebaseResponse` 的内容、metadata 和空结果语义。
- REQ-001.3: If 查询词为空白，then veadk-java shall 保持现有行为，不发起远端查询并返回空结果。
- REQ-001.4: If 未配置有效 KnowledgeBase API Key，then veadk-java shall 继续使用现有 AK/SK 查询路径。
- REQ-001.5: If API Key 查询返回无效凭据、过期、权限不足、collection 不存在、网络错误或服务端错误，then veadk-java shall 按该查询入口既有的可定位失败语义返回或抛错，且不得自动改用 AK/SK 重放同一请求。

Gherkin:

```gherkin
Scenario: 使用显式 API Key 查询已有 KnowledgeBase collection
  Given 调用方已显式配置有效的 KnowledgeBase API Key
  And 目标 collection 已存在
  When 调用方通过 Viking KnowledgeBase 执行查询
  Then SDK 使用显式 API Key 对查询请求鉴权
  And 返回结果结构与 AK/SK 模式一致
```

### REQ-002: Viking Memory 数据面支持 API Key

- User Story: As a 使用 Viking Memory 的 Java 开发者, I want 使用 API Key 添加和查询记忆, so that 应用可通过限定的数据面凭据使用已有 memory collection。
- Priority: P0
- Description: `VikingMemoryService` 的记忆添加与查询应使用统一解析出的 Memory API Key；现有事件筛选、metadata、返回对象及 reactive 调用语义保持不变。

Acceptance Requirements (EARS):
- REQ-002.1: When 存在有效 Memory API Key 且会话包含可写入的用户消息时，veadk-java shall 使用该 API Key 向已有 collection 添加记忆。
- REQ-002.2: When 存在有效 Memory API Key 且调用记忆查询时，veadk-java shall 使用该 API Key 查询已有 collection。
- REQ-002.3: When API Key 数据面请求成功时，veadk-java shall 保持现有添加成功/失败语义以及 `SearchMemoryResponse` 的结构和空结果语义。
- REQ-002.4: If 会话中没有符合现有筛选规则的用户消息，then veadk-java shall 保持现有行为，不发起记忆添加请求并正常完成。
- REQ-002.5: If 未配置有效 Memory API Key，then veadk-java shall 继续使用现有 AK/SK 数据面路径。
- REQ-002.6: If API Key 数据面请求鉴权失败，then veadk-java shall 返回可定位失败或沿用既有异常语义，且不得自动改用 AK/SK 重放同一请求。

Gherkin:

```gherkin
Scenario: 使用环境变量 API Key 添加并查询记忆
  Given DATABASE_VIKINGMEM_API_KEY 包含有效 API Key
  And 目标 Memory collection 已存在
  When 调用方添加包含有效用户消息的会话并随后查询记忆
  Then 两类数据面请求均使用该 API Key 鉴权
  And 添加与查询的 Java 返回语义与 AK/SK 模式一致
```

### REQ-003: API Key 配置解析与优先级

- User Story: As a Java SDK 开发者, I want 通过显式参数或环境变量配置 API Key, so that 本地、测试和部署环境具有可预测的覆盖规则。
- Priority: P0
- Description: KnowledgeBase 与 Memory 分别解析自己的 API Key，不交叉复用；具体 Java API 形态由技术设计沿用现有 SDK 风格确定。

Acceptance Requirements (EARS):
- REQ-003.1: The system shall 为 KnowledgeBase 和 Memory 分别提供显式 API Key 配置入口。
- REQ-003.2: The system shall 从 `DATABASE_VIKING_API_KEY` 读取 KnowledgeBase API Key，从 `DATABASE_VIKINGMEM_API_KEY` 读取 Memory API Key。
- REQ-003.3: When 显式参数与对应环境变量均为有效 API Key 时，veadk-java shall 使用显式参数。
- REQ-003.4: When 显式参数无效而对应环境变量有效时，veadk-java shall 使用环境变量。
- REQ-003.5: If API Key 为 `null`、空串、纯空白、大小写不敏感的 `none` 或 `null`，then veadk-java shall 将其视为未配置并继续下一优先级。
- REQ-003.6: If 对应 API Key 均未有效配置，then veadk-java shall 进入现有 AK/SK 路径；AK 或 SK 缺失时沿用现有缺少配置的失败语义。
- REQ-003.7: The system shall 不使用 KnowledgeBase API Key 作为 Memory 凭据，也不使用 Memory API Key 作为 KnowledgeBase 凭据。

Gherkin:

```gherkin
Scenario Outline: 解析数据面 API Key
  Given 显式 API Key 为 <explicit>
  And 对应环境变量 API Key 为 <environment>
  When SDK 解析本次数据面鉴权配置
  Then 选择结果为 <selected>

  Examples:
    | explicit | environment | selected |
    | valid-A  | valid-B     | valid-A  |
    | blank    | valid-B     | valid-B  |
    | none     | absent      | AK/SK    |
    | NULL     | absent      | AK/SK    |
```

### REQ-004: 管理面鉴权边界

- User Story: As a 只持有数据面 API Key 的 Java 开发者, I want SDK 不触发无权限的 collection 管理调用, so that 初始化可直接使用预先创建的 collection。
- Priority: P0
- Description: API Key 不改变管理面权限边界。双凭据并存时，数据面使用 API Key，管理面仍使用 AK/SK；API Key-only 时直接跳过自动管理。

Acceptance Requirements (EARS):
- REQ-004.1: When 同时存在有效 API Key 与完整 AK/SK 时，veadk-java shall 对数据面使用 API Key，并对初始化期间既有的 collection 检查/自动创建使用 AK/SK。
- REQ-004.2: When 处于 API Key-only 模式时，veadk-java shall 跳过 collection 存在性检查与自动创建，不发起任何需 AK/SK 的管理面请求。
- REQ-004.3: When API Key-only 模式指向不存在的 collection 时，veadk-java shall 让首次数据面请求返回可定位的 collection 不存在或服务端错误，不得尝试自动创建。
- REQ-004.4: When KnowledgeBase 调用 `addDoc` 或其他现有文档/TOS 管理入口时，veadk-java shall 不使用 KnowledgeBase API Key；该操作仍要求现有 AK/SK 能力。
- REQ-004.5: If 同时没有有效 API Key 与完整 AK/SK，then veadk-java shall 在需要鉴权前以不包含凭据内容的错误明确指出缺少必要配置。

Gherkin:

```gherkin
Scenario: API Key-only 初始化跳过 collection 管理
  Given 调用方只配置有效 API Key
  And 调用方指定一个已有 collection
  When 创建 Viking KnowledgeBase 或 Viking Memory 实例
  Then SDK 不检查或创建 collection
  And 首次数据面调用使用 API Key
```

### REQ-005: AK/SK 与公开 SDK 兼容

- User Story: As a 已有 veadk-java 用户, I want 升级后无需修改 AK/SK 集成, so that 现有应用不发生回归。
- Priority: P0
- Description: API Key 是兼容性新增能力，不移除或改变现有入口的用途。

Acceptance Requirements (EARS):
- REQ-005.1: When 未配置有效 API Key 且 AK/SK 有效时，veadk-java shall 保持现有 KnowledgeBase 查询、`addDoc`、collection 自动管理、Memory 添加与查询行为。
- REQ-005.2: The system shall 保持现有公开构造方式与 `KnowledgeBase` builder 调用可编译；新增 API Key 配置不得迫使现有用户修改调用代码。
- REQ-005.3: The system shall 保持既有同步、异步与 Google ADK service 入口的业务返回类型。
- REQ-005.4: If 用户只设置了与本次需求无关的 API Key 环境变量，then veadk-java shall 不改变对应 Viking 能力的现有 AK/SK 行为。

### REQ-006: 安全反馈与凭据保护

- User Story: As a SDK 使用者与维护者, I want 鉴权错误可定位但不泄密, so that 能排障且不会暴露凭据。
- Priority: P0
- Description: 错误可说明操作类型、collection、鉴权模式和服务端错误摘要，但不能包含任何凭据或完整鉴权头。

Acceptance Requirements (EARS):
- REQ-006.1: The system shall 不在日志、异常 message、测试断言、文档示例或调试输出中记录真实 API Key、AK、SK 或完整 `Authorization` 值。
- REQ-006.2: When API Key 请求失败时，veadk-java shall 提供足以区分 KnowledgeBase 查询、Memory 添加、Memory 查询及配置缺失的错误上下文。
- REQ-006.3: If SDK 记录请求或响应摘要，then veadk-java shall 排除或脱敏凭据字段与鉴权 header；Debug 级别也适用。
- REQ-006.4: When 验证安全行为时，测试 shall 使用假凭据，并断言日志/异常中不存在假凭据原文和完整鉴权头。

## 5. SDK 配置契约

### 5.1 配置项

| 能力 | 显式配置语义 | 环境变量 | 用途 |
| --- | --- | --- | --- |
| Viking KnowledgeBase | 可选 `apiKey` 语义，具体 Java API 形态由技术设计确定 | `DATABASE_VIKING_API_KEY` | 查询已有 KnowledgeBase collection |
| Viking Memory | 可选 `apiKey` 语义，具体 Java API 形态由技术设计确定 | `DATABASE_VIKINGMEM_API_KEY` | 添加、查询已有 Memory collection 中的记忆 |
| 管理面与 KnowledgeBase `addDoc` | 沿用既有 AK/SK 配置 | `VOLCENGINE_ACCESS_KEY` + `VOLCENGINE_SECRET_KEY` | collection 管理及不在 API Key 范围内的操作 |

### 5.2 选择规则

| 配置状态 | 数据面鉴权 | 初始化管理行为 |
| --- | --- | --- |
| 有效显式 API Key；无论环境变量是否存在 | 显式 API Key | 有完整 AK/SK 时沿用管理面预检查；否则跳过 |
| 显式 API Key 无效；有效环境变量 API Key | 环境变量 API Key | 有完整 AK/SK 时沿用管理面预检查；否则跳过 |
| 无有效 API Key；完整 AK/SK | AK/SK | 沿用现有检查与自动创建 |
| 无有效 API Key；AK/SK 不完整或缺失 | 无可用鉴权 | 在需要鉴权前明确失败 |

补充规则：

- “有效”按 Glossary 的定义判断。
- 数据面调用一旦选定 API Key，不得因 401、403、无权限、过期或其他服务端失败而静默切换为 AK/SK。
- 双凭据并存不表示 API Key 可以访问管理面；两类凭据按操作类型分工。
- API Key 是 Secret，不得出现在 `toString`、序列化配置快照或可公开诊断信息中。

## 6. Backend/Data

### 6.1 操作范围矩阵

| 领域 | 操作 | API Key | AK/SK | 结果要求 |
| --- | --- | --- | --- | --- |
| KnowledgeBase | 查询已有 collection | 支持且优先 | 未配置 API Key 时使用 | 保持既有结果映射与空结果语义 |
| KnowledgeBase | collection 检查/创建及其他管理 | 不支持 | 使用 | API Key-only 时跳过自动管理 |
| KnowledgeBase | `addDoc`、TOS 上传、文档/切片管理 | 不支持 | 使用 | 保持既有行为 |
| Memory | 向已有 collection 添加记忆 | 支持且优先 | 未配置 API Key 时使用 | 保持既有完成/失败语义 |
| Memory | 查询已有 collection 中的记忆 | 支持且优先 | 未配置 API Key 时使用 | 保持 `SearchMemoryResponse` 与空结果语义 |
| Memory | collection 检查/创建及其他管理 | 不支持 | 使用 | API Key-only 时跳过自动管理 |

### 6.2 异常与边界

- 无效、过期或权限不足的 API Key: 失败应归因到当前数据面请求，不重放、不降级到其他凭据。
- API Key-only 且 collection 不存在: 由首次数据面调用暴露可定位错误，不自动创建。
- 双凭据并存但 AK/SK 管理预检查失败: 不得把管理失败误报为 API Key 无效；沿用既有初始化失败边界。
- 空查询、无有效用户消息、空检索结果: 保持当前短路或空结果行为。
- 数据模型: 本需求不新增 SDK 持久化数据，不涉及 schema、迁移、回填或数据清理。

## 7. Documentation Requirements

- README 中文与英文说明均须增加两项 API Key 环境变量及其适用范围。
- 文档须说明显式配置入口、配置优先级、空值处理、AK/SK 回退和双凭据分工。
- 文档须明确仅配置 API Key 时必须使用预先存在的 collection，SDK 不检查或创建 collection。
- KnowledgeBase 示例不得暗示 API Key 可用于 `addDoc`、TOS 上传或文档管理。
- 所有示例只能使用明显的占位符或假凭据，不得提交真实 Secret。

## 8. API Design

本需求不新增或修改服务端 OpenAPI、Webhook 或 CLI。Java SDK 将新增可显式提供 API Key 的公开配置能力；本 Spec 只规定其产品语义、优先级和兼容性，具体 Java API 形态由技术设计在保留现有构造方式的前提下确定。

## 9. Metrics

本需求不新增埋点、Dashboard 或指标口径。不得将 API Key、AK/SK、Authorization 或请求/响应正文作为日志或 Metrics label。

## 10. NFR / DFX

| NFR ID | 类别 | 要求 | 验收方法 |
| --- | --- | --- | --- |
| NFR-001 | 兼容性 | 未配置 API Key 时，现有 AK/SK 路径、公开构造方式、同步/异步入口和返回类型不变。 | 编译现有示例并运行既有及新增回归测试。 |
| NFR-002 | 安全 | API Key、AK/SK 与完整 Authorization 不得出现在日志、异常、测试输出、文档或配置序列化中。 | 使用唯一假 Secret 覆盖成功与失败路径，检查输出不含 Secret 原文。 |
| NFR-003 | 权限边界 | API Key 仅用于本 Spec 定义的数据面；管理面继续使用 AK/SK，鉴权失败不跨凭据自动重试。 | 通过请求捕获或 fake client 验证每类操作实际采用的鉴权模式及调用次数。 |
| NFR-004 | 可靠性 | 新增鉴权方式不得改变现有请求超时、调用次数或空结果语义；失败不得因自动 fallback 产生重复写入。 | 覆盖 API Key 失败、网络失败、Memory 添加失败、Knowledge 查询空结果与一次请求断言。 |
| NFR-005 | 可测试性 | 配置优先级、空值清洗、双凭据、API Key-only、AK/SK-only 及缺失凭据均须可在无真实 Secret 的自动化测试中验证。 | 使用环境变量隔离、mock/fake client 和假凭据执行单元/契约测试。 |
| NFR-006 | 地域与版本 | 保持 veadk-java 当前 Volcengine endpoint、JDK 17+ 与 Maven artifact 边界；不新增 BytePlus 或 VeFaaS IAM 承诺。 | 编译与测试矩阵使用仓库当前 JDK/Maven 基线，并确认无新增产品形态分支。 |

### 10.1 发布与回滚

- 本次为向后兼容的 SDK 能力新增，不要求 Feature Gate。
- 回滚到变更前版本会失去 API Key 能力；只配置 API Key 的应用将无法继续访问 Viking 数据面，发布说明须提示这一点。
- 若 API Key 路径出现问题，使用方可在确认权限与安全要求后移除 API Key 配置并恢复既有 AK/SK 模式；SDK 不在单次失败中自动执行该降级。
- 不涉及数据迁移或不可逆数据变更。Memory 添加本身是既有业务写入行为，其幂等性与重试语义不因本需求改变。

## 11. Acceptance Scenarios

| 场景 | 前置条件 | 操作 | 预期结果 |
| --- | --- | --- | --- |
| Knowledge 显式 API Key | 显式 key 与环境 key 同时有效，已有 collection | 同步/异步查询 | 使用显式 key；结果结构与 AK/SK 一致 |
| Knowledge 环境 API Key | 显式 key 为空，环境 key 有效，已有 collection | 查询 | 使用 `DATABASE_VIKING_API_KEY` |
| Memory 显式 API Key | 显式 key 与环境 key 同时有效，已有 collection | 添加并查询记忆 | 两类数据面操作均使用显式 key |
| Memory 环境 API Key | 显式 key 为 `none`，环境 key 有效 | 添加并查询记忆 | 使用 `DATABASE_VIKINGMEM_API_KEY` |
| API Key-only 初始化 | 有效 API Key，无 AK/SK | 构造 KnowledgeBase / Memory | 不调用 collection 检查或创建 |
| 双凭据 | API Key 与完整 AK/SK 同时存在 | 初始化后执行数据面 | 管理面使用 AK/SK；数据面使用 API Key |
| AK/SK 兼容 | 无有效 API Key，AK/SK 完整 | 初始化并执行原有操作 | 行为与变更前一致 |
| 占位空值 | 显式/环境值为空白、`none` 或 `NULL` | 解析配置 | 视为未配置并进入下一优先级 |
| 鉴权失败 | API Key 无效或无权限 | 发起数据面请求 | 可定位失败；不重放、不切换 AK/SK；不泄露 key |
| collection 不存在 | API Key-only，目标 collection 不存在 | 首次数据面请求 | 返回可定位错误；不尝试创建 |
| Knowledge `addDoc` | 仅有 Knowledge API Key | 添加 TOS 文档 | 不使用 API Key 执行；按缺少管理凭据处理 |
| Memory 空会话 | 会话无符合现有规则的用户消息 | 添加记忆 | 不调用远端并正常完成 |

## 12. Traceability

| REQ / NFR | 产品契约位置 | QA focus | 证据来源 |
| --- | --- | --- | --- |
| REQ-001 | §4 KnowledgeBase、§6 操作矩阵 | API Key 查询、空查询、结果兼容、失败不降级 | 已确认需求；Java KnowledgeBase 现状；Python Knowledge API Key 实现 |
| REQ-002 | §4 Memory、§6 操作矩阵 | 添加、查询、空会话、返回兼容、失败不降级 | 已确认需求；Java Memory 现状；Python Memory API Key 实现 |
| REQ-003 | §5 配置契约 | 显式/环境/AK-SK 优先级、空值清洗、领域隔离 | 原始优先级要求；负责人确认；Python 配置测试 |
| REQ-004 | §5 选择规则、§6 管理面边界 | API Key-only、双凭据、collection 不存在、`addDoc` | 负责人确认；Java 自动管理现状；Python 跳过预检查行为 |
| REQ-005 | §2 Goals、§10 兼容性 | 现有入口编译、AK/SK 回归、同步/异步返回 | Java 当前公开类与测试 |
| REQ-006 / NFR-002 | §4 安全、§10 NFR | 假 Secret 不出现在日志/异常/输出 | 上游安全约束；AgentKit 日志与脱敏规范 |
| NFR-003 / NFR-004 | §10 NFR | 鉴权路由、单次请求、失败与空结果 | 已确认需求与现有 SDK 行为 |
| NFR-005 / NFR-006 | §10 NFR | 无真实凭据测试、JDK/Maven/地域基线 | veadk-java 仓库与同步产品上下文 |
