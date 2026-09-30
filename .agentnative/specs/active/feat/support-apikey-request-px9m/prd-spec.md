# Review Summary

- 本次需求: 为 `veadk-java` 的 Viking KnowledgeBase 与 Viking Memory 数据面调用增加 API Key 鉴权，并保持 AK/SK/IAM 管理链路兼容。
- 需求类型: Java SDK 新功能、配置契约与鉴权行为变更。
- 变更面: Viking KnowledgeBase、Viking Memory、公共配置与环境变量、失败语义、SDK 文档与验证；不涉及: 页面、控制面服务、服务端 API/协议、数据库 Schema、非 Viking 能力；待确认: 无。
- 变更面判断依据: Meego story `7379276505`、上游澄清产物、`veadk-java` 当前 Viking 配置/服务/wrapper/测试、`veadk-python` Viking KnowledgeBase/Memory 参考实现与文档。
- 差异判断: 产品形态仅涉及 Java SDK；Volcengine 与 BytePlus 的凭据和 endpoint 选择存在配置差异；本地、托管环境遵循同一配置优先级；升级版本须向后兼容现有 AK/SK 用户。依据: 上游澄清产物与 Python 参考实现。
- 本次变更关键信息:
  - 显式 API Key 参数优先于对应环境变量；空白、`None`、`null` 按未配置处理并允许环境变量回退。
  - KnowledgeBase API Key 仅覆盖已有知识库搜索；Memory API Key 覆盖 Java 当前已有的记忆添加与查询数据面能力。
  - 仅配置 API Key 时，不得因初始化读取 AK/SK 或执行 collection 管理预检查而阻塞数据面调用。
  - 数据面 API Key 失败须暴露可定位异常，不得伪装为空结果；任何输出不得泄露 API Key。
- Review 重点:
  - 数据面与管理面边界是否明确，尤其是初始化阶段不误触发管理调用。
  - 配置优先级、Volcengine/BytePlus 差异以及 AK/SK/IAM fallback 是否完整且兼容。
  - 失败与真正空结果是否可区分，凭据是否在日志、异常和测试输出中得到保护。

## Scope Note

- Implemented sections: 背景与目标、范围与非目标、配置契约、功能需求、异常语义、兼容性、NFR、验收场景、追溯矩阵。
- Not involved: 页面与交互、服务端公开 API/协议、数据库 Schema/迁移、埋点指标、发布版本与排期、非 Viking SDK 能力。
- Needs confirmation: 无。
- Reason: 冻结需求与仓库证据已明确为单一 Java SDK 仓库中的新鉴权能力；关键范围和行为已由负责人确认与 `veadk-python` 当前实现范围保持一致。

## 1. 背景与目标

### 1.1 背景

当前 `veadk-java` 的 Viking KnowledgeBase 与 Viking Memory 初始化和请求链路以 `VOLCENGINE_ACCESS_KEY` / `VOLCENGINE_SECRET_KEY` 为主要凭据。Java SDK 用户即使只访问已经存在的知识库或记忆 collection，也必须提供 AK/SK；这与 `veadk-python` 已支持的 API Key 数据面模式不一致。

本需求让 Java SDK 用户可用权限更聚焦的 Viking API Key 完成知识库检索、记忆保存和记忆查询，同时保留原有 AK/SK/IAM 链路处理管理操作和兼容已有应用。

### 1.2 目标

- GOAL-001: 用户能够通过显式参数或环境变量为 Viking KnowledgeBase 与 Viking Memory 配置 API Key。
- GOAL-002: 对已有 collection 的数据面调用，有有效 API Key 时优先使用 API Key；没有 API Key 时继续使用既有 AK/SK/IAM 链路。
- GOAL-003: 仅 API Key 配置可完成本需求覆盖的数据面调用，不因缺少 AK/SK 在初始化阶段失败。
- GOAL-004: 升级后保持既有 AK/SK 用户、查询参数、结果结构、空结果和数据语义兼容。
- GOAL-005: 失败可定位且凭据不进入日志、异常、测试快照或文档真实值。

### 1.3 用户与场景

| 用户 | 前置条件 | 场景 | 价值 |
| --- | --- | --- | --- |
| Java SDK 开发者 | 已有可由 API Key 访问的 Viking 知识库 | 通过 Agent 检索知识 | 无需为只读数据面访问配置 AK/SK |
| Java SDK 开发者 | 已有可由 API Key 访问的记忆 collection | 保存会话记忆、查询长期记忆 | 使用 API Key 完成运行时数据访问 |
| 现有 Java SDK 用户 | 已配置 AK/SK 或托管 IAM | 升级 SDK 后继续使用原能力 | 无需迁移配置或改变业务代码语义 |

## 2. 范围与非目标

### 2.1 In Scope

- `veadk-java` 中 Viking KnowledgeBase 搜索已有知识库。
- `veadk-java` 中 Viking Memory 当前公开的数据面能力：`addSessionToMemory` 与 `searchMemory`。
- 若 Java SDK 在本需求实施时已有同一 Viking Memory 后端的用户画像查询能力，该能力遵循相同 API Key 鉴权规则；本需求不要求为此新增独立公开能力。
- 显式配置、环境变量、配置清洗与优先级。
- API Key 与 AK/SK/IAM 的数据面/管理面选择规则。
- Volcengine 与 BytePlus 的相关 Viking 配置对齐。
- README 或等价用户文档、单元测试与真实数据面验收。

### 2.2 Non-Goals

- NG-001: 不改变 Viking 服务端的 API、权限模型、资源归属、配额、限流或 API Key 生成方式。
- NG-002: 不要求 API Key 支持 collection 创建、查询、列举或删除等管理操作。
- NG-003: 不要求 API Key 支持知识库文档导入、TOS 上传、文档/切片管理。
- NG-004: 不新增 Java SDK 当前不存在的知识库或记忆业务能力。
- NG-005: 不改变 Ark 模型、WebSearch、TLS Trace、RunCode、Mem0、OpenSearch 等非 Viking 能力的鉴权。
- NG-006: 不在本 Spec 决定 HTTP 客户端、第三方依赖、类、方法签名或内部错误类型。
- NG-007: 不承诺具体 SDK 版本、发布日期、灰度比例或性能数值。

### 2.3 仓库职责

- 当前 Repo: `volcengine/veadk-java`，负责 Java SDK 配置入口、鉴权选择、数据面行为、兼容性、文档与测试。
- 参考 Repo: `volcengine/veadk-python`，仅作为已确认产品行为的证据，不是本需求的修改目标。
- Viking 服务端与凭据平台: 负责验证 API Key 和返回业务/鉴权结果，不在本需求中变更。

## 3. 核心规则与配置契约

### 3.1 鉴权选择

| 操作类别 | API Key 已配置 | API Key 未配置 |
| --- | --- | --- |
| KnowledgeBase 搜索 | 优先使用 KnowledgeBase API Key | 使用既有 AK/SK/IAM |
| Memory 添加、查询 | 优先使用 Memory API Key | 使用既有 AK/SK/IAM |
| collection 管理 | 不使用 API Key 作为必达凭据；使用 AK/SK/IAM | 使用 AK/SK/IAM |
| 知识库文档/TOS 管理 | 不使用 API Key 作为必达凭据；使用 AK/SK/IAM | 使用 AK/SK/IAM |

规则补充：

- API Key 只在对应产品域内生效；KnowledgeBase API Key 不替代 Memory API Key，反之亦然。
- 同时存在 API Key 与 AK/SK/IAM 时，数据面优先 API Key，管理面继续使用 AK/SK/IAM。
- 仅有 API Key 时，SDK 跳过需要管理凭据的初始化预检查或自动创建动作，并假设用户访问的是已有 collection；这不代表 collection 已被 SDK 验证存在。
- API Key 无效或无权限时，不得自动静默降级为 AK/SK，以免掩盖显式选择和权限配置错误。

### 3.2 配置优先级与空值

所有本需求涉及的可显式配置项遵循：

1. 有效显式参数；
2. 对应环境变量；
3. 文档约定的默认值；
4. 无默认值时视为未配置。

API Key 显式值或环境变量在 trim 后为空，或大小写不敏感地等于 `none` / `null` 时，均视为未配置。无效显式 API Key 允许回退到对应环境变量；有效显式值不得被环境变量覆盖。

### 3.3 KnowledgeBase 配置

| 语义配置项 | 环境变量 | 默认/回退 | 作用范围 |
| --- | --- | --- | --- |
| API Key | `DATABASE_VIKING_API_KEY` | 无 | 已有知识库搜索 |
| Project | `DATABASE_VIKING_PROJECT` | `default` | 搜索与管理请求的项目定位 |
| Region | `DATABASE_VIKING_REGION`，其次 `REGION` | Volcengine 为 `cn-beijing`；BytePlus 按其支持区域规则 | endpoint 与请求区域 |
| Resource ID | `DATABASE_VIKING_RESOURCE_ID` | 空 | 服务端支持时用于知识库资源定位 |
| Version | `DATABASE_VIKING_VERSION` | `2` | 管理能力的知识库版本语义；不得改变 API Key 搜索结果语义 |
| Base URL | `DATABASE_VIKING_BASE_URL` | 按 cloud provider 与 region 推导 | Viking KnowledgeBase endpoint |
| Cloud Provider | `AGENTKIT_CLOUD_PROVIDER`，其次 `CLOUD_PROVIDER` | `volcengine` | Volcengine/BytePlus 配置选择 |
| 管理凭据 | Volcengine: `VOLCENGINE_ACCESS_KEY` / `VOLCENGINE_SECRET_KEY` / `VOLCENGINE_SESSION_TOKEN`；BytePlus: `BYTEPLUS_ACCESS_KEY` / `BYTEPLUS_SECRET_KEY` / `BYTEPLUS_SESSION_TOKEN` | 既有凭据/IAM 规则 | 管理操作与无 API Key 数据面 |

### 3.4 Memory 配置

| 语义配置项 | 环境变量 | 默认/回退 | 作用范围 |
| --- | --- | --- | --- |
| API Key | `DATABASE_VIKINGMEM_API_KEY` | 无 | 已有 collection 的记忆数据面访问 |
| Project | `DATABASE_VIKINGMEM_PROJECT` | `default` | 记忆 collection 定位 |
| Region | `DATABASE_VIKING_REGION`，其次 `REGION` | Volcengine 为 `cn-beijing`；BytePlus 按其支持区域规则 | endpoint 与请求区域 |
| Memory Type | `DATABASE_VIKINGMEM_MEMORY_TYPE` | `sys_event_v1,sys_profile_v1` | 记忆写入/检索过滤 |
| Base URL | `DATABASE_VIKINGMEM_BASE_URL` | 按 cloud provider 与 region 推导 | Viking Memory endpoint |
| Cloud Provider | `AGENTKIT_CLOUD_PROVIDER`，其次 `CLOUD_PROVIDER` | `volcengine` | Volcengine/BytePlus 配置选择 |
| 管理凭据 | Volcengine: `VOLCENGINE_ACCESS_KEY` / `VOLCENGINE_SECRET_KEY` / `VOLCENGINE_SESSION_TOKEN`；BytePlus: `BYTEPLUS_ACCESS_KEY` / `BYTEPLUS_SECRET_KEY` / `BYTEPLUS_SESSION_TOKEN` | 既有凭据/IAM 规则 | 管理操作与无 API Key 数据面 |

说明：Java public API 的具体类型和命名由技术设计确定，但必须能表达上述语义，且不得要求用户把 API Key 拼入 URL、日志字段或业务请求体。

## 4. 功能需求

### REQ-001: KnowledgeBase API Key 搜索

**User Story**

> As a Java SDK 开发者, I want 使用 API Key 搜索已有 Viking 知识库, so that 我无需为数据面检索配置 AK/SK。

**Acceptance Requirements**

- REQ-001.1: **When** 有效 KnowledgeBase API Key 已解析，且用户执行非空知识库搜索，SDK **shall** 使用 API Key 鉴权访问目标 project/collection。
- REQ-001.2: **When** 未解析到 KnowledgeBase API Key，SDK **shall** 保持既有 AK/SK/IAM 搜索链路。
- REQ-001.3: **When** API Key 搜索成功，SDK **shall** 保持现有内容、metadata、`topK`、filter、rerank、chunk diffusion 与空结果语义。
- REQ-001.4: **If** query 为空且现有 Java 行为直接返回空结果，新增鉴权能力 **shall not** 改变该行为。

### REQ-002: Memory API Key 数据面访问

**User Story**

> As a Java SDK 开发者, I want 使用 API Key 保存和查询 Viking 记忆, so that Agent 运行时无需持有管理凭据。

**Acceptance Requirements**

- REQ-002.1: **When** 有效 Memory API Key 已解析，且用户调用 `addSessionToMemory`，SDK **shall** 使用该 API Key 完成记忆添加。
- REQ-002.2: **When** 有效 Memory API Key 已解析，且用户调用 `searchMemory`，SDK **shall** 使用该 API Key 完成记忆查询。
- REQ-002.3: **When** 未解析到 Memory API Key，SDK **shall** 保持既有 AK/SK/IAM 记忆链路。
- REQ-002.4: API Key 模式 **shall not** 改变现有会话消息筛选、用户标识、`topK`、memory type、返回结构与空结果语义。
- REQ-002.5: 若同一后端已存在用户画像查询，该数据面调用 **shall** 使用同一 Memory API Key 选择规则。

### REQ-003: 显式配置与环境变量优先级

**Acceptance Requirements**

- REQ-003.1: **When** 有效显式 API Key 与对应环境变量同时存在，SDK **shall** 使用显式值。
- REQ-003.2: **When** 显式 API Key 缺省或属于 §3.2 定义的无效空值，且环境变量有效，SDK **shall** 使用环境变量值。
- REQ-003.3: **When** 显式值和环境变量均无效，SDK **shall** 将 API Key 视为未配置并选择既有 AK/SK/IAM 链路。
- REQ-003.4: Project、region、resource ID、version、base URL、memory type、cloud provider 的显式配置与环境变量 **shall** 遵循相同优先级规则。

### REQ-004: 仅 API Key 初始化与管理边界

**Acceptance Requirements**

- REQ-004.1: **When** 仅配置对应 API Key 且没有 AK/SK/IAM，SDK **shall not** 在构造或初始化 Viking 数据面客户端时因读取 AK/SK 失败。
- REQ-004.2: **When** 仅配置 API Key，SDK **shall not** 为数据面可用性强制执行 collection 查询、创建或其他需要管理凭据的预检查。
- REQ-004.3: **When** 用户在仅 API Key 模式执行管理类操作，SDK **shall** 明确暴露缺少管理凭据或管理请求失败，且不得声称 API Key 覆盖该操作。
- REQ-004.4: **When** API Key 与管理凭据同时存在，SDK **shall** 允许数据面与管理面分别使用其约定凭据。

### REQ-005: 失败、空结果与安全反馈

**Acceptance Requirements**

- REQ-005.1: **If** API Key 无效、过期、无权限、网络失败、服务端非成功业务码或响应无法解析，SDK **shall** 向调用方暴露可定位异常。
- REQ-005.2: API Key 鉴权失败或请求失败 **shall not** 被转换为正常空结果或“collection 不存在”。
- REQ-005.3: **When** 服务端成功响应但没有匹配数据，SDK **shall** 返回现有空结果语义。
- REQ-005.4: 日志和异常可以包含操作类型、目标 project/collection、region、非敏感服务端错误码与 request ID，但 **shall not** 包含 API Key、Authorization 值、AK/SK、session token 或其他完整凭据。

### REQ-006: 用户文档与兼容说明

**Acceptance Requirements**

- REQ-006.1: 用户文档 **shall** 说明 KnowledgeBase/Memory 的显式 API Key 配置、对应环境变量与优先级。
- REQ-006.2: 用户文档 **shall** 区分数据面与管理面范围，并说明仅 API Key 模式要求目标 collection 已存在。
- REQ-006.3: 用户文档 **shall** 说明未配置 API Key 时继续使用 AK/SK/IAM，以及 Volcengine/BytePlus 相关配置差异。
- REQ-006.4: 示例只使用明显的占位值，不得包含真实或形似可用的凭据。

## 5. 关键场景与异常语义

### SCN-001: 显式 KnowledgeBase API Key 搜索

```gherkin
Given 目标知识库已存在且显式 KnowledgeBase API Key 有效
And 环境变量中存在另一个 KnowledgeBase API Key
When 用户执行知识库搜索
Then SDK 使用显式 API Key 鉴权
And 返回结构与 AK/SK 模式一致
```

### SCN-002: Memory 环境变量回退

```gherkin
Given 用户未显式配置 Memory API Key
And DATABASE_VIKINGMEM_API_KEY 有效
And 未配置 AK/SK
When 用户初始化 Memory service 并添加或查询记忆
Then 初始化不因缺少 AK/SK 失败
And 数据面请求使用环境变量中的 API Key
```

### SCN-003: AK/SK 兼容

```gherkin
Given 用户未配置任何 Viking API Key
And 既有 AK/SK 或 IAM 凭据有效
When 用户执行原有 Viking 数据面或管理操作
Then SDK 继续使用既有凭据链路
And 原有业务结果与数据语义保持不变
```

### SCN-004: API Key 无权限

```gherkin
Given API Key 无权访问目标 collection
When 用户执行数据面调用
Then SDK 向调用方暴露可定位的鉴权或权限异常
And 不返回正常空结果
And 日志与异常不包含凭据明文
```

### SCN-005: 仅 API Key 执行管理操作

```gherkin
Given 仅配置 API Key 且未配置管理凭据
When 用户执行 collection 管理或知识库文档导入
Then SDK 明确暴露管理凭据缺失或管理请求失败
And 不将该操作错误地改为 API Key 数据面请求
```

## 6. 兼容性与差异

### 6.1 向后兼容

- 新 API Key 配置为可选；未配置时必须保持现有 AK/SK/IAM 行为。
- 不改变现有 Viking public 数据面方法的业务参数与返回数据语义。
- 不要求现有用户迁移环境变量、collection 或业务数据。
- 不涉及持久化 Schema 或数据迁移；回滚到旧版本后 API Key 配置将不生效，AK/SK/IAM 路径仍是可用回退前提。

### 6.2 产品形态与环境差异

| 维度 | 结论 |
| --- | --- |
| Java / Python | Java 对齐 Python 的配置与鉴权产品行为，但不要求复制 Python 内部实现或新增 Java 当前不存在的业务 API。 |
| Volcengine / BytePlus | cloud provider 决定凭据变量、region 和 endpoint 规则；显式配置优先级与数据面/管理面边界一致。 |
| 本地 / 托管环境 | 显式配置和环境变量规则一致；托管 IAM 只属于既有管理凭据或 API Key 缺省路径。 |
| SDK 版本 | 新版本新增可选配置，既有配置保持兼容；不引入破坏性 public API 变更。 |

## 7. NFR / DFX

### NFR-SEC-001: 凭据保护

- API Key、Authorization、AK/SK 与 session token 不得出现在日志、异常消息、Trace 属性、测试快照或文档真实值中。
- 使用唯一假 Secret 验证成功、服务端拒绝、网络异常和解析异常路径，断言输出中不存在完整凭据。

### NFR-COMPAT-001: 兼容性

- 未配置 API Key 的既有测试与典型使用方式必须继续通过。
- KnowledgeBase/Memory 数据结构、filter、`topK`、memory type、rerank 与空结果语义不得因鉴权模式改变。

### NFR-REL-001: 失败可判别

- 数据面失败与成功空结果必须可区分。
- 不要求 SDK 在 API Key 失败后自动换用 AK/SK；调用方通过修正 API Key 或移除 API Key 显式选择回退。

### NFR-TEST-001: 验证范围

- 仓库单元测试至少覆盖配置清洗与优先级、KnowledgeBase/Memory 鉴权选择、仅 API Key 初始化、AK/SK fallback、管理边界、API Key 失败和脱敏。
- E2E 至少覆盖一个真实 KnowledgeBase 搜索，以及 Memory 添加后查询的闭环；另覆盖无权限或无效 API Key 与成功空结果的区分。
- 不设新增性能目标；仅 API Key 初始化不得额外发起管理调用，数据面不得引入无界重试或重复请求。

## 8. 验收清单

- AC-001: 显式 KnowledgeBase API Key 可搜索已有知识库，且优先于 `DATABASE_VIKING_API_KEY`。
- AC-002: `DATABASE_VIKING_API_KEY` 可在无显式值时用于知识库搜索。
- AC-003: 显式 Memory API Key 可完成 `addSessionToMemory` 与 `searchMemory`，且优先于 `DATABASE_VIKINGMEM_API_KEY`。
- AC-004: `DATABASE_VIKINGMEM_API_KEY` 可在无显式值时完成记忆添加与查询。
- AC-005: 空白、`None`、`null` 显式值按未配置处理并允许环境变量回退。
- AC-006: 仅配置 API Key、未配置 AK/SK 时，数据面初始化和调用不被管理预检查阻塞。
- AC-007: 未配置 API Key 时，现有 AK/SK/IAM 数据面与管理能力保持可用。
- AC-008: API Key 与 AK/SK/IAM 同时存在时，数据面使用 API Key，管理面使用管理凭据。
- AC-009: 仅 API Key 模式下，管理操作明确失败且不被误报为 API Key 已覆盖。
- AC-010: 无效、过期、无权限、服务端失败、网络失败与解析失败均暴露异常，不转换为空结果。
- AC-011: 成功但无匹配数据继续返回现有空结果。
- AC-012: 日志、异常、测试输出和文档中不出现凭据明文。
- AC-013: Volcengine 与 BytePlus 的相关配置选择符合 §3，显式配置优先于环境变量。
- AC-014: 用户文档完整说明配置、优先级、数据面范围、管理边界和兼容路径。
- AC-015: 仓库单元测试与真实 E2E 覆盖 NFR-TEST-001 所列范围。

## 9. Traceability

| Requirement | 验收 | 主要证据 |
| --- | --- | --- |
| REQ-001 | AC-001、AC-002、AC-011 | 上游澄清 §3/§5；Python KnowledgeBase backend 与文档；Java Viking KnowledgeBase 当前实现 |
| REQ-002 | AC-003、AC-004、AC-011 | 上游澄清 §3/§5；Python Viking Memory backend 与文档；Java Viking Memory 当前实现 |
| REQ-003 | AC-001 至 AC-005、AC-013 | 原始需求“显式配置参数 > 环境变量”；Python 配置清洗与测试 |
| REQ-004 | AC-006 至 AC-009 | 上游澄清的数据面/管理面边界；Java 当前初始化会读取 AK/SK 并检查 collection |
| REQ-005 | AC-010 至 AC-012 | 上游澄清失败行为；日志与观测脱敏规范 |
| REQ-006 | AC-014 | 上游澄清文档验收；Java README 当前仅说明 AK/SK |
| NFR-SEC-001 | AC-012 | AgentKit 日志规范 rev 20、观测数据脱敏手册 rev 204 |
| NFR-COMPAT-001 | AC-007、AC-011、AC-013 | 原始需求与上游澄清的兼容要求 |
| NFR-REL-001 | AC-010、AC-011 | Python 参考实现的数据面非成功码异常行为 |
| NFR-TEST-001 | AC-015 | 冻结 workflow scope 与上游验证要求 |

## 10. 证据与假设

### 10.1 证据来源

- Meego story `7379276505`: 原始目标与配置优先级。
- 上游共享产物 `veadk-java Viking 数据面支持 API Key 请求需求澄清`: 负责人已确认范围、配置、请求形态和失败行为与 Python 当前实现一致。
- `veadk-java` 基线 `edb0a5e477c04a9290a4cd7c746eb3209cb1690d`: 当前 KnowledgeBase 配置只要求 AK/SK；KnowledgeBase 与 Memory 初始化会执行 collection 检查/创建；现有 wrapper 把部分数据面失败转为空结果。
- `veadk-python` 参考实现与文档: `DATABASE_VIKING_API_KEY`、`DATABASE_VIKINGMEM_API_KEY`、空值清洗、仅 API Key 跳过管理预检查、数据面优先 API Key、管理操作保留 AK/SK/IAM。
- 《ArkClaw/Agentkit 管控日志打印规范》: https://bytedance.larkoffice.com/docx/ETK2dt4oRoeuvkxOFgvclKnWnYb，revision 20，规范强度为 AgentKit 管控链路 norm；本需求采纳其中“不记录完整 Request/Response、Token、Secret”的跨组件安全原则，不套用 Go 日志组件要求。
- 《ArkClaw/Agentkit观测数据脱敏手册》: https://bytedance.larkoffice.com/docx/VYRSdHS4UoAFbdxUnkqceXLtnWg，revision 204，角色为 security-guide；本需求采纳 API Key/Authorization 不进入观测数据和使用假 Secret 验证的原则，Friday/Collector 配置细节不适用于开源 Java SDK。

### 10.2 显式假设

- API Key 的权限和有效期由 Viking 服务端决定，Java SDK 不在本地解释或扩展权限。
- API Key 搜索/记忆请求所需的精确 endpoint、header 或 SDK auth 对象属于技术设计；产品行为必须与本 Spec 一致。
- Java 当前没有公开的 Viking 用户画像查询能力，因此本需求不单独新增该 API；未来若在同一后端暴露该能力，应复用 Memory API Key 规则。
- 当前无数据库、前端或服务端变更，不需要 schema 迁移、页面状态或服务端错误码设计。

### 10.3 Open Questions

无阻塞性 Open Questions。Java public 配置类型、依赖选择、请求客户端、异常类型和测试替身属于后续技术设计。
