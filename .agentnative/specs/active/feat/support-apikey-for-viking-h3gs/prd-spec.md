# Review Summary

- 本次需求：为 veadk-java 的 Viking Knowledgebase 查询，以及 Viking Memory 记忆添加与检索增加 API Key 鉴权，同时保持既有 AK/SK 能力兼容。
- 工作项优先级：P2。
- 需求类型：Java SDK 公共配置契约、后端数据面鉴权、安全与兼容性。
- 变更范围：Viking Knowledgebase、Viking Memory、配置优先级、失败语义、用户文档与验证；不涉及前端、数据库、OpenAPI/RPC、非 Viking 组件或 BytePlus 新能力。
- 关键边界：API Key 仅用于访问已有 collection 的数据面能力；collection、文档和切片等管理能力仍使用 AK/SK；API key-only 初始化不得被 AK/SK 专属管理预检查阻塞。
- 证据基线：冻结需求澄清、veadk-java 当前分支、veadk-python 固定提交 `31d2c67be6fb9bd7f3139de42d2615c2c4a73e6f`、Meego 工作项 `7379276505` 和已命中的 AgentKit 安全规范离线 Source Card。
- Review 重点：API Key 数据面边界、显式配置优先级、双凭证共存、AK/SK 向后兼容、失败不降级和敏感信息保护。
- 待确认：无阻塞项。Java API 形态、依赖与传输适配属于后续技术设计。

# Spec：veadk-java Viking 数据面支持 API Key 请求

## 1. 背景与问题

veadk-java 当前使用 Viking Knowledgebase 与 Viking Memory 时依赖 `VOLCENGINE_ACCESS_KEY` 和 `VOLCENGINE_SECRET_KEY` 进行 AK/SK 鉴权。只有既有 Viking collection 的 API Key、但没有 AK/SK 的 Java SDK 用户，无法完成知识库查询、记忆添加或记忆检索。

veadk-python 已提供两类彼此独立的 API Key 配置与数据面访问能力。本需求让 Java SDK 用户获得一致的核心能力，同时确保现有 AK/SK 用户、管理操作和非 Viking 能力不受影响。

本 Spec 以 veadk-java 当前分支基线 `8a9d00ad183ceb15751cd9529f152a3022a2707d` 和 veadk-python 固定参考提交 `31d2c67be6fb9bd7f3139de42d2615c2c4a73e6f` 为行为证据；未来 Python 行为变化不会自动扩大本次范围。

### 1.1 证据

| ID | 来源 | 支撑结论 |
| --- | --- | --- |
| EVD-001 | 冻结输入《Viking 支持 API Key 请求 - 需求澄清文档》 | API Key 覆盖范围、配置名、显式配置优先、API key-only 行为、AK/SK 兼容和 Volcengine 边界已经收敛。 |
| EVD-002 | veadk-java 当前 Viking Knowledgebase 配置、backend 与 wrapper | Knowledgebase 当前使用 AK/SK，并在初始化阶段执行 collection 检查或创建；公开数据面包含查询，文档添加属于范围外能力。 |
| EVD-003 | veadk-java 当前 Viking Memory service 与 wrapper | Memory 当前使用 AK/SK，并在初始化阶段执行 collection 检查或创建；公开数据面包含记忆添加和检索。 |
| EVD-004 | veadk-java 当前公共 SDK 入口、环境变量工具和中英文 README | 当前没有两类 Viking API Key 配置；既有公共调用方式和 AK/SK 文档需要保持兼容。 |
| EVD-005 | veadk-python 固定参考提交 `31d2c67be6fb9bd7f3139de42d2615c2c4a73e6f` 的 Viking 实现、测试与文档 | Knowledgebase 与 Memory 使用独立 API Key；有效显式值优先；空值回退环境变量；API key-only 跳过 collection 管理预检查；管理操作仍需 AK/SK/IAM。 |
| EVD-006 | Meego 工作项 `7379276505` 的最新只读快照 | 工作项为 P2 新需求，涉及后端和有效性测试，并要求安全技术评审。 |
| EVD-007 | 《ArkClaw/Agentkit 管控日志打印规范》revision 20 与《ArkClaw/Agentkit观测数据脱敏手册》revision 204 的离线 Source Card | API Key、AK/SK 和 Authorization 等凭证不得明文进入日志或观测数据；原文当前无访问权限，精确时效性规则标记为 `needs_live_verification`。 |

## 2. 目标与非目标

### 2.1 目标

- GOAL-001：Java SDK 用户可使用 API Key 查询已有 Viking Knowledgebase collection，公共结果语义与既有 AK/SK 路径一致。
- GOAL-002：Java SDK 用户可使用 API Key 向已有 Viking Memory collection 添加记忆并检索记忆，公共结果语义与既有 AK/SK 路径一致。
- GOAL-003：Knowledgebase 与 Memory 均支持实例级显式 API Key 和约定环境变量，且来源选择确定、可验证。
- GOAL-004：仅配置 API Key 的用户可直接使用上述数据面能力，不被 AK/SK 专属的 collection 管理预检查阻塞。
- GOAL-005：未配置 API Key 的既有用户无需修改代码或 AK/SK 配置即可继续使用原有能力。

### 2.2 非目标

- NG-001：不替换、下线或改变现有 AK/SK 鉴权能力。
- NG-002：不让 API Key 获得 collection 创建、状态查询、列举、更新或删除等管理能力。
- NG-003：Knowledgebase 文档添加、文档或切片管理以及依赖 TOS 上传的能力不纳入 API Key 范围。
- NG-004：不新增 veadk-python 已有但 veadk-java 当前没有公开的 Viking 业务能力。
- NG-005：不改变业务数据结构、召回排序、`topK`、rerank、chunk diffusion、memory type 等非鉴权语义。
- NG-006：不扩大 BytePlus、Ark、OpenSearch、Mem0、WebSearch、Trace 或其他非 Viking 能力。
- NG-007：不新增前端、数据库 Schema、OpenAPI、RPC、Webhook、CLI 或业务指标。

## 3. 术语与范围

| 术语 | 定义 |
| --- | --- |
| API Key | Viking 专用访问凭证；Knowledgebase 与 Memory 使用彼此独立的配置项。 |
| AK/SK | 火山引擎 Access Key / Secret Key；保留为既有鉴权方式，并继续承担本次范围外的管理操作。 |
| 数据面 | 本需求中仅指 Knowledgebase 查询，以及 Memory 记忆添加和检索。 |
| 管理操作 | collection 的检查、创建、列举、更新、删除，以及 Knowledgebase 文档或切片管理等不属于本次 API Key 范围的操作。 |
| API key-only | 存在有效 Viking API Key，但不存在一对有效 AK/SK 的配置状态。 |
| 有效 API Key | 去除首尾空白后非空，且不等于大小写不敏感的 `none` 或 `null`；该定义只表示本地配置有效，不代表服务端一定接受。 |

### 3.1 配置和能力边界

| 能力 | 显式配置语义 | 环境变量 | API Key 可用范围 |
| --- | --- | --- | --- |
| Viking Knowledgebase | 当前实例可选的 Knowledgebase API Key | `DATABASE_VIKING_API_KEY` | 查询已有 collection |
| Viking Memory | 当前实例可选的 Memory API Key | `DATABASE_VIKINGMEM_API_KEY` | 向已有 collection 添加记忆和检索记忆 |

- 两类 API Key 相互独立，不互相回退，也不复用 `MODEL_AGENT_API_KEY`。
- 本次只新增 Volcengine 支持；BytePlus 保持当前能力边界。
- 目标 collection 必须预先存在。API key-only 只免除初始化阶段的管理预检查，不免除资源存在和权限前提。

## 4. 功能需求

### REQ-001：Knowledgebase 查询支持 API Key

- User Story：作为 Java SDK 用户，我希望使用 Viking Knowledgebase API Key 查询已有 collection，从而在没有 AK/SK 时也能完成只读检索。
- Description：当 Knowledgebase 存在有效 API Key 时，已有 collection 的查询使用该 API Key。collection、query、过滤、结果数量和后处理等既有业务语义，以及公共返回结构和空结果语义，不因鉴权方式变化。

Acceptance Requirements（EARS）：

- REQ-001.1：When 用户查询已有 Viking Knowledgebase collection 且存在有效 API Key, the Java SDK shall 使用 API Key 完成鉴权，而不要求同时存在 AK/SK。
- REQ-001.2：When API Key 查询成功, the Java SDK shall 返回与 AK/SK 查询相同的公共结果类型和字段语义。
- REQ-001.3：When API Key 查询没有命中结果, the Java SDK shall 返回现有空结果语义，而不是将其视为鉴权失败。
- REQ-001.4：If 用户调用 Knowledgebase 文档添加或 collection 管理操作, then the Java SDK shall 不把 API Key 当作管理凭证，并保持既有 AK/SK 要求。

```gherkin
Scenario: 仅使用 API Key 查询已有知识库
  Given 用户已配置有效的 Knowledgebase API Key 且目标 collection 已存在
  And 用户未配置 AK/SK
  When 用户通过 Java SDK 查询该 collection
  Then SDK 使用 API Key 发起查询
  And 返回结果的公共语义与 AK/SK 路径一致
  And 初始化过程不执行需要 AK/SK 的 collection 管理预检查
```

### REQ-002：Memory 记忆添加与检索支持 API Key

- User Story：作为 Java SDK 用户，我希望使用 Viking Memory API Key 写入并检索已有 collection 中的记忆，从而让运行时记忆能力不依赖 AK/SK。
- Description：当 Memory 存在有效 API Key 时，现有记忆添加和检索数据面行为使用 API Key；消息筛选、metadata、用户过滤、memory type、结果数量、异步完成或失败传播和公共返回类型保持现有业务语义。

Acceptance Requirements（EARS）：

- REQ-002.1：When 用户向已有 Viking Memory collection 添加有效会话记忆且存在有效 API Key, the Java SDK shall 使用 API Key 完成该数据面请求。
- REQ-002.2：When 用户检索已有 Viking Memory collection 且存在有效 API Key, the Java SDK shall 使用 API Key 完成该数据面请求并返回现有公共响应类型。
- REQ-002.3：When 会话中没有符合现有规则的可写入消息, the Java SDK shall 保持现有不发起请求并正常完成的行为。
- REQ-002.4：If 用户只有 API Key, then the Java SDK shall 不执行需要 AK/SK 的 collection 存在性检查或自动创建。

```gherkin
Scenario: 仅使用 API Key 添加并检索记忆
  Given 用户已配置有效的 Memory API Key 且目标 collection 已存在
  And 用户未配置 AK/SK
  When 用户添加包含有效用户消息的会话并随后检索记忆
  Then 两个数据面请求均使用 API Key
  And 添加完成和检索结果保持现有 Java 公共接口语义
  And 初始化过程不检查或创建 collection
```

### REQ-003：配置来源、空值与优先级

- User Story：作为 Java SDK 用户，我希望实例级参数和环境变量之间有确定的选择规则，从而避免多实例或部署配置产生隐式覆盖。
- Description：Knowledgebase 与 Memory 分别支持显式 API Key，并分别读取 `DATABASE_VIKING_API_KEY` 与 `DATABASE_VIKINGMEM_API_KEY`。

Acceptance Requirements（EARS）：

- REQ-003.1：When 有效显式 API Key 与对应环境变量同时存在, the Java SDK shall 使用显式值。
- REQ-003.2：When 显式值为 `null`、空字符串、纯空白或大小写不敏感的 `none` / `null`, the Java SDK shall 将其视为未配置并读取对应环境变量。
- REQ-003.3：When 对应环境变量也是无效值, the Java SDK shall 将 API Key 视为未配置并进入 REQ-004 的 AK/SK 兼容路径。
- REQ-003.4：When Knowledgebase 与 Memory 配置不同 API Key, the Java SDK shall 在各自能力中独立使用对应值。
- REQ-003.5：If 已选中的 API Key 被服务端判定为无效、过期或无权限, then the Java SDK shall 暴露该失败，不得在同一次数据面调用中静默改用环境变量或 AK/SK。

```gherkin
Scenario: 有效显式 API Key 覆盖环境变量
  Given 对应环境变量为一个有效 API Key
  And 当前 SDK 实例显式配置了另一个有效 API Key
  When 用户调用对应 Viking 数据面能力
  Then SDK 使用显式 API Key

Scenario: 无效显式值回退环境变量
  Given 对应环境变量为一个有效 API Key
  And 当前 SDK 实例显式值为纯空白
  When 用户调用对应 Viking 数据面能力
  Then SDK 使用环境变量中的 API Key
```

### REQ-004：AK/SK 向后兼容与双凭证共存

- User Story：作为现有 Java SDK 用户，我希望升级后原有 AK/SK 配置继续工作，从而避免新鉴权能力造成迁移中断。
- Description：API Key 是可选增量配置。没有有效 API Key 时继续使用既有 AK/SK；两种凭证同时存在时，数据面优先 API Key，管理操作继续使用 AK/SK。

Acceptance Requirements（EARS）：

- REQ-004.1：When 未配置有效 API Key 且存在有效 AK/SK, the Java SDK shall 保持现有 Viking 数据面与管理行为。
- REQ-004.2：When 有效 API Key 与 AK/SK 同时存在, the Java SDK shall 对本次范围内的数据面操作使用 API Key，并允许既有管理操作继续使用 AK/SK。
- REQ-004.3：When 既无有效 API Key 也无一对有效 AK/SK, the Java SDK shall 在发出目标请求前或最接近鉴权边界处给出明确的凭证缺失错误。
- REQ-004.4：The Java SDK shall 保持现有无需新参数的公共构造方式和调用代码可用，不要求 AK/SK 用户迁移到 API Key。

### REQ-005：可诊断且不泄密的失败行为

- User Story：作为 Java SDK 用户，我希望能区分配置缺失、鉴权或权限失败、资源问题与正常空结果，从而安全定位问题。
- Description：API Key 路径保持现有 Java 公共失败边界，不把服务端失败吞并为空结果或普通成功；任何可观察信息均不得暴露凭证。

Acceptance Requirements（EARS）：

- REQ-005.1：If 凭证缺失, then the Java SDK shall 给出能识别 Knowledgebase 或 Memory 所需配置来源的错误，且不包含任何凭证值。
- REQ-005.2：If API Key 无效、过期或权限不足, then the Java SDK shall 向调用方暴露可识别的鉴权或权限失败，不得返回与“无结果”相同的成功响应。
- REQ-005.3：If 目标 collection 不存在或不可访问, then the Java SDK shall 暴露资源或权限失败，不得在 API key-only 模式下尝试自动创建 collection。
- REQ-005.4：If 网络、超时、服务端或响应异常发生, then the Java SDK shall 按现有公共异常边界向调用方传播可诊断失败。
- REQ-005.5：The Java SDK shall 不在任何日志级别、异常消息、Trace、测试失败输出或示例中输出 API Key、AK/SK、Token、Cookie 或完整 Authorization 信息。

### REQ-006：用户文档与示例可发现

- User Story：作为 Java SDK 用户，我希望从中英文文档了解 API Key 的配置和能力边界，从而正确选择 API Key 或 AK/SK。

Acceptance Requirements（EARS）：

- REQ-006.1：The Java SDK documentation shall 说明 `DATABASE_VIKING_API_KEY` 用于查询已有 Viking Knowledgebase collection。
- REQ-006.2：The Java SDK documentation shall 说明 `DATABASE_VIKINGMEM_API_KEY` 用于已有 Viking Memory collection 的记忆添加与检索。
- REQ-006.3：The Java SDK documentation shall 说明显式配置优先、无效显式值回退环境变量、API key-only 不执行管理预检查，以及管理操作仍需 AK/SK。
- REQ-006.4：The Java SDK documentation and examples shall 仅使用明显的占位凭证，不提供真实或看似真实的 Secret。

## 5. 关键业务规则

### 5.1 鉴权选择矩阵

| 配置状态 | Knowledgebase 查询 | Memory 添加或检索 | collection 或文档管理 |
| --- | --- | --- | --- |
| 有效 API Key，无 AK/SK | API Key | API Key | 初始化跳过管理预检查；显式调用管理操作时报告需要管理凭证 |
| 有效 API Key，有 AK/SK | API Key | API Key | AK/SK |
| 无有效 API Key，有 AK/SK | AK/SK，保持现状 | AK/SK，保持现状 | AK/SK，保持现状 |
| 无有效 API Key，无 AK/SK | 明确凭证缺失失败 | 明确凭证缺失失败 | 明确凭证缺失失败 |

### 5.2 请求、结果与失败边界

- API Key 只允许发送到对应的 Volcengine Viking 服务，不得转发给 TOS、Ark、BytePlus 或其他下游。
- 除鉴权及服务端所需资源定位语义外，请求字段和公共响应语义保持现状。
- 正常空结果、本地凭证缺失、服务端鉴权或权限失败、资源不存在和依赖异常必须可区分。
- 已选择 API Key 后，同一次数据面调用不得切换身份重试，以免掩盖错误配置或以非预期身份执行请求。
- API Key 的服务端权限不由 SDK 扩张；目标 collection 的存在性和访问授权仍由 Viking 服务判定。

## 6. 非功能约束

### 6.1 兼容性

- 新能力为可选增量；未配置 API Key 时，现有公共调用方式、AK/SK 环境变量、结果类型和业务行为保持兼容。
- Knowledgebase 与 Memory 的 API Key 配置互相隔离，非 Viking backend 不受影响。
- 不删除或改名现有公共配置与调用入口。

### 6.2 安全与可观测性

- API Key、AK/SK、Token、Cookie、私钥和完整 Authorization 信息属于敏感凭证，不得以明文出现在源码、日志、异常、Trace、测试快照或文档示例中。
- 可记录鉴权模式、操作类型和受控的非敏感资源标识，但不得记录完整请求头或完整配置对象。
- 自动化验证使用明显的假凭证，并覆盖正常和异常路径的防泄露断言。
- 本需求完成开发后须按工作项要求通过安全技术评审；离线 Source Card 的精确时效性规则仍需具备权限的后续节点回源核验。

### 6.3 可靠性与发布边界

- API key-only 初始化不得发起 collection 管理预检查，避免无管理权限的调用阻塞合法数据面请求。
- 正常空结果与依赖失败保持语义分离。
- 本次不涉及数据迁移，不要求新增 Feature Gate；版本说明需标注新增配置、支持范围和兼容边界。

## 7. 验收与验证范围

### 7.1 业务验收

- [主流程-Knowledgebase] 仅配置有效 Knowledgebase API Key、目标 collection 已存在时，可完成查询并获得与 AK/SK 路径一致的公共结果语义。
- [主流程-Memory 添加] 仅配置有效 Memory API Key、目标 collection 已存在时，可完成记忆添加。
- [主流程-Memory 检索] 仅配置有效 Memory API Key、目标 collection 已存在时，可完成记忆检索并获得现有公共响应语义。
- [配置优先级] 显式参数和对应环境变量同时有效且值不同时使用显式值；显式值无效时回退环境变量。
- [凭证隔离] Knowledgebase 与 Memory 使用各自配置，不交叉复用，也不复用 Ark 模型 API Key。
- [兼容性] 仅 AK/SK、API Key 与 AK/SK 共存、无凭证和既有公共调用方式均符合 REQ-004。
- [API key-only] 初始化不因 collection 查询或自动创建等管理预检查阻断数据面调用。
- [范围边界] 文档添加和 collection 管理不把 API Key 当作管理凭证；BytePlus 与非 Viking 能力不扩展。
- [失败与安全] 鉴权、权限、资源和依赖失败可诊断且不等同于空结果，所有可观察输出不泄露假 Secret。
- [文档] 中英文用户文档对配置名称、优先级、数据面范围、已有 collection 前提和 AK/SK 管理边界的说明一致。

### 7.2 最小验证范围

- 配置解析：显式值、环境变量、显式优先、空字符串、纯空白、`none`、`null`、两类 Key 隔离。
- Knowledgebase：API Key 查询、空结果、既有查询语义、文档添加边界、API key-only 初始化。
- Memory：API Key 添加、API Key 检索、无有效消息、API key-only 初始化。
- 兼容性：AK/SK-only、双凭证、无凭证、既有公共调用方式和非 Viking backend。
- 异常与安全：无效、过期或无权限 Key，资源不存在，网络、超时、服务端或响应异常，以及敏感值不泄露。
- 文档：中英文说明一致，环境变量名和支持范围准确。

## 8. 追溯矩阵

| REQ ID | 主要证据 | 下游关注 | 验收焦点 |
| --- | --- | --- | --- |
| REQ-001 | EVD-001、EVD-002、EVD-005 | Knowledgebase 数据面和资源定位 | 查询、空结果、文档添加边界 |
| REQ-002 | EVD-001、EVD-003、EVD-005 | Memory 数据面和初始化边界 | 添加、检索、无有效消息、管理预检查 |
| REQ-003 | EVD-001、EVD-004、EVD-005 | 配置解析和凭证隔离 | 优先级、空值、双 Key、失败不降级 |
| REQ-004 | EVD-001、EVD-002、EVD-003、EVD-004 | 公共契约与 AK/SK 兼容 | AK/SK-only、双凭证、无凭证 |
| REQ-005 | EVD-001、EVD-006、EVD-007 | 错误语义和凭证保护 | 鉴权、权限、资源、依赖失败与泄露反例 |
| REQ-006 | EVD-001、EVD-004 | 用户文档和示例 | 中英文一致性与占位凭证 |

## 9. 假设、待确认与变更摘要

### 9.1 显式假设

- “查询”对应 veadk-java 已有 Viking Knowledgebase 查询能力；“记忆添加等数据面接口”包含 Viking Memory 已有的记忆添加和检索能力。该假设已由冻结需求澄清和 Python 固定基线支持。
- API Key 只用于已有 Viking 资源的数据面访问；collection、文档、切片与 TOS 相关管理能力继续使用 AK/SK 或等价管理凭证。
- 具体 Java API 形态、服务依赖和资源定位字段由后续技术设计在不改变本 Spec 行为契约的前提下确定。

### 9.2 待确认

- 无阻塞当前 Spec 交付的需求语义问题。
- 后续安全评审需在具备权限时核验 EVD-007 原文的最新版本；当前可执行的凭证不泄露底线不受影响。

### 9.3 相对历史稿的变更摘要

- 保持 `REQ-001` 至 `REQ-006` 及其验收编号稳定。
- 按最新 Meego 快照将工作项优先级明确为 P2，不再把各需求误标为 P0。
- 将 Python 参考固定到提交 `31d2c67be6fb9bd7f3139de42d2615c2c4a73e6f`，避免后续上游变化隐式扩大范围。
- 明确安全技术评审要求和公共规范回源状态；移除具体 Java 类、构造器、HTTP/SDK 方案等实现预设。
