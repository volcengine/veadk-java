# Review Summary

- 本次需求: 为 `veadk-java` 现有 Viking Knowledgebase 与 Viking Memory 数据面能力增加 API Key 鉴权，同时保留 AK/SK 兼容链路。
- 需求类型: SDK 配置与鉴权能力、后端行为、兼容性、安全与地域差异。
- 变更面: Functional Requirements、SDK 配置契约、鉴权分流、异常反馈、NFR / DFX、Traceability；不涉及: 前端 UX、数据模型或存储迁移、OpenAPI / RPC 协议、Metrics / Dashboard；待确认: 无。
- 变更面判断依据: Meego 工作项 `7379276505`、已确认需求澄清产物、`veadk-java` 当前 Viking 配置与请求链路、`veadk-python` 参考实现及测试。
- 差异判断: 产品形态差异涉及 Java SDK 与 Python SDK 的能力对齐；站点差异涉及 Volcengine 与 BytePlus；环境差异涉及显式配置与环境变量；版本差异要求新增能力不破坏既有 AK/SK 用户。依据为已确认需求及 Python 参考实现提交 `31d2c67be6fb9bd7f3139de42d2615c2c4a73e6f`。
- 本次变更关键信息:
  - API Key 覆盖 Knowledgebase search、Memory search 和 Memory add session；集合管理与知识入库不纳入 API Key 范围。
  - 有效显式 API Key 高于环境变量；数据面有 API Key 时优先使用 API Key，无 API Key 时回退到既有 AK/SK。
  - API Key-only 初始化跳过需要管理面凭据的集合检查或自动创建，目标集合必须已存在。
  - BytePlus 的 provider、凭据、region 与 host 行为须与 Python 参考实现对齐。
- Review 重点:
  - 数据面与管理面边界是否准确，特别是知识入库仍不使用 API Key。
  - 配置优先级、空值语义、API Key-only 初始化与 AK/SK 兼容性是否完整。
  - BytePlus 与 Volcengine 的 region / host 差异是否可被下游设计和测试唯一解释。
  - 鉴权失败可诊断性与 API Key 不进入日志、异常、Trace、Metric 的安全约束。

## Scope Note

- Implemented sections: Context、Goals and Non-Goals、Glossary、Functional Requirements、Backend Behavior、SDK Configuration Contract、NFR / DFX、Acceptance Scenarios、Traceability。
- Not involved: 前端 UX、数据模型与持久化、OpenAPI / RPC、Metrics / Dashboard。
- Needs confirmation: 无。
- Reason: 需求和仓库证据将变更限定在单个 Java SDK 仓库的 Viking 配置解析与远端请求鉴权；不新增页面、服务端协议或持久化结构。

# Spec: veadk-java Viking 数据面支持 API Key 请求

## 1. Context

- 当前 `veadk-java` 的 Viking Knowledgebase 与 Viking Memory 请求均依赖 `VOLCENGINE_ACCESS_KEY` 和 `VOLCENGINE_SECRET_KEY`。只使用查询或记忆读写的 SDK 用户仍需配置长期 AK/SK。
- `veadk-python` 已将部分 Viking 数据面能力扩展为 API Key 鉴权，并保留 AK/SK 管理面与兼容链路。本需求要求 Java SDK 在相同能力边界上对齐。
- 目标用户是使用 `veadk-java` 接入既有 Viking 集合的开发者。用户价值是可用权限边界更窄的 API Key 完成查询和记忆读写，并可按 SDK 实例显式覆盖部署环境中的凭据。
- 本需求为 P2 新功能，实际影响仓库仅为 `volcengine/veadk-java`，不涉及前端。

### 1.1 Evidence baseline

- 冻结需求: `artifacts/workflow-node-artifact-yew2fdv11cjxj0wb9npp/README.md`。
- Java 基线: `veadk-java` 提交 `8a9d00ad183ceb15751cd9529f152a3022a2707d`；当前 Viking wrapper 只接受 AK/SK，Knowledgebase 初始化和 Memory 初始化均会执行集合管理检查。
- Python 行为参考: `veadk-python` 提交 `31d2c67be6fb9bd7f3139de42d2615c2c4a73e6f`；参考实现和测试明确了 API Key 环境变量、Bearer 鉴权、管理面分流、API Key-only 初始化及 BytePlus 行为。
- 公共规范: 配置应集中解析和校验，敏感凭据不得写入日志、Trace、Metric、测试输出或仓库示例。

## 2. Goals and Non-Goals

### 2.1 Goals

- G-001: 允许 Java SDK 用户只配置有效 API Key 和已存在的 Viking 集合，即可完成纳入范围的数据面请求。
- G-002: 提供显式配置与环境变量两种 API Key 来源，并保证显式有效值优先。
- G-003: API Key 与 AK/SK 并存时按数据面/管理面分流；未配置 API Key 时保持既有 AK/SK 行为。
- G-004: 对齐 Python 参考实现中 Volcengine / BytePlus 的 provider、region、host 与凭据环境变量行为。
- G-005: 让配置缺失、鉴权失败和远端失败可诊断，同时不泄露任何完整凭据。

### 2.2 Non-Goals

- NG-001: 不使用 API Key 调用集合查询、集合创建等管理面操作。
- NG-002: 不使用 API Key 执行 Knowledgebase 文档上传或 `addDoc`；该能力继续沿用既有 AK/SK 及相关存储凭据链路。
- NG-003: 不新增 Java 当前没有暴露的 Viking 数据面能力，例如新的用户画像接口。
- NG-004: 不改变 OpenSearch、Mem0、TOS、模型服务等非 Viking 能力的鉴权方式。
- NG-005: 不新增或修改 Viking 服务端 OpenAPI / RPC，不新增数据表或数据迁移。
- NG-006: 不在本阶段规定 Java 类、构造函数、HTTP 客户端或依赖版本等实现方案。

## 3. Glossary

| 术语 | 定义 |
|---|---|
| 数据面 | 本需求中特指 Knowledgebase search、Memory search 和 Memory add session 三类现有 Java SDK 操作。 |
| 管理面 | 集合存在性检查、集合创建等管理集合生命周期的操作。 |
| 显式配置 | 用户在创建或配置对应 Viking SDK 能力时直接传入的配置值。 |
| API Key-only | 存在有效 API Key，但不存在完整有效的 AK/SK 对。 |
| 有效 API Key | 去除首尾空白后非空，且不是不区分大小写的字面值 `none` 或 `null`。 |

## 4. Functional Requirements

### REQ-001: API Key 配置解析与优先级

- User Story: As a Java SDK 使用者, I want 通过显式参数或环境变量配置 API Key, so that 我可以按实例覆盖部署环境中的凭据。
- Priority: P2
- Description: Knowledgebase 与 Memory 分别解析自己的 API Key；只有有效显式值才覆盖环境变量，无效显式值继续回退环境变量。

Acceptance Requirements (EARS):

- REQ-001.1: When Knowledgebase 获得有效显式 API Key 时, the SDK shall 使用该值并忽略 `DATABASE_VIKING_API_KEY`。
- REQ-001.2: When Memory 获得有效显式 API Key 时, the SDK shall 使用该值并忽略 `DATABASE_VIKINGMEM_API_KEY`。
- REQ-001.3: When 显式 API Key 缺失或无效且对应环境变量包含有效值时, the SDK shall 使用环境变量值。
- REQ-001.4: If API Key 为 `null`、空字符串、纯空白、字面值 `none` 或字面值 `null`（大小写不敏感）, then the SDK shall 将其视为未配置。
- REQ-001.5: The SDK shall 独立解析 Knowledgebase 与 Memory 的 API Key，不得在两个子系统间互相回退。

Gherkin:

```gherkin
Scenario: 显式 Knowledgebase API Key 覆盖环境变量
  Given 环境变量 DATABASE_VIKING_API_KEY 为 env-key
  And 用户显式配置 API Key 为 explicit-key
  When 用户执行 Knowledgebase search
  Then SDK 使用 explicit-key 进行鉴权
  And 请求不使用 env-key
```

### REQ-002: Knowledgebase search 使用 API Key

- User Story: As a Knowledgebase 使用者, I want 仅使用 API Key 查询既有集合, so that 我无需为只读查询配置 AK/SK。
- Priority: P2
- Description: 仅 Knowledgebase search 纳入本次 Knowledgebase API Key 范围；查询参数与返回对象保持原有 SDK 语义。

Acceptance Requirements (EARS):

- REQ-002.1: When 存在有效 Knowledgebase API Key 时, the SDK shall 对 search 请求使用 `Authorization: Bearer <api_key>`，且不得同时使用 AK/SK 签名该请求。
- REQ-002.2: When search 使用 API Key 时, the SDK shall 保留集合名、project、查询文本、topK、metadata filter、rerank 和 chunk diffusion 等当前适用的业务语义。
- REQ-002.3: When API Key search 成功时, the SDK shall 返回与 AK/SK 模式相同的 `KnowledgebaseEntry` 结果语义。
- REQ-002.4: When API Key search 无匹配结果时, the SDK shall 保持当前空结果语义。
- REQ-002.5: If 未配置有效 Knowledgebase API Key, then the SDK shall 沿用既有 AK/SK search 链路。

Gherkin:

```gherkin
Scenario: API Key-only 查询已存在的知识库集合
  Given 用户仅配置有效 DATABASE_VIKING_API_KEY
  And 目标 Knowledgebase 集合已存在
  When 用户执行 search
  Then 请求使用 Bearer API Key 鉴权
  And SDK 返回与 AK/SK 模式一致语义的查询结果
```

### REQ-003: Memory search 与 add session 使用 API Key

- User Story: As a Viking Memory 使用者, I want 仅使用 API Key 查询和添加会话记忆, so that 我无需为记忆数据面操作配置 AK/SK。
- Priority: P2
- Description: Memory search 与 add session 使用相同的已解析 Memory API Key；现有过滤、metadata 和返回语义不因鉴权方式改变。

Acceptance Requirements (EARS):

- REQ-003.1: When 存在有效 Memory API Key 时, the SDK shall 对 Memory search 与 add session 使用 API Key 鉴权，且不得为这两个请求要求 AK/SK。
- REQ-003.2: When 执行 Memory search 时, the SDK shall 保持 userId、memory type、topK 与结果转换语义。
- REQ-003.3: When 执行 Memory add session 时, the SDK shall 保持消息筛选、用户 metadata、assistant 标识与时间信息等现有业务语义。
- REQ-003.4: When API Key add session 成功时, the SDK shall 保持当前成功反馈语义，且新增记忆可被后续查询命中。
- REQ-003.5: If 未配置有效 Memory API Key, then the SDK shall 沿用既有 AK/SK search 与 add session 链路。

Gherkin:

```gherkin
Scenario: API Key-only 添加并查询会话记忆
  Given 用户仅配置有效 DATABASE_VIKINGMEM_API_KEY
  And 目标 Memory 集合已存在
  When 用户添加一段有效会话记忆并按同一 userId 查询
  Then 添加和查询请求均使用 API Key 鉴权
  And 查询结果包含已添加记忆
```

### REQ-004: 数据面与管理面鉴权分流

- User Story: As a 同时拥有 API Key 与 AK/SK 的使用者, I want SDK 按接口能力选择凭据, so that 数据面优先采用 API Key 且管理能力继续可用。
- Priority: P2
- Description: API Key 不扩大到集合管理或 Knowledgebase 文档写入；API Key-only 模式不得因隐式管理操作阻塞数据面初始化。

Acceptance Requirements (EARS):

- REQ-004.1: When 有效 API Key 与完整有效 AK/SK 同时存在时, the SDK shall 对纳入范围的数据面请求优先使用 API Key，并对集合管理及 Knowledgebase 文档写入继续使用 AK/SK。
- REQ-004.2: When 处于 API Key-only 模式时, the SDK shall 跳过初始化阶段的集合存在性检查和自动创建，不得因缺少 AK/SK 阻止数据面对象初始化。
- REQ-004.3: When API Key-only 用户访问不存在的集合时, the SDK shall 在实际数据面调用处返回与远端响应一致的可诊断失败，不得尝试以 API Key 创建集合。
- REQ-004.4: If API Key 与完整有效 AK/SK 均不可用, then the SDK shall 在首次需要远端鉴权前给出明确的配置缺失反馈。
- REQ-004.5: When 未配置 API Key 且 AK/SK 有效时, the SDK shall 保持当前初始化、集合管理、Knowledgebase 查询/写入和 Memory 查询/写入行为。

Gherkin:

```gherkin
Scenario: API Key 与 AK/SK 并存时按平面分流
  Given 用户配置了有效 API Key 和完整 AK/SK
  When SDK 初始化集合并执行数据面查询
  Then 集合管理请求使用 AK/SK
  And 数据面查询使用 API Key
```

### REQ-005: Volcengine 与 BytePlus 兼容

- User Story: As a BytePlus 或 Volcengine 使用者, I want Java SDK 与 Python SDK 使用一致的云厂商配置规则, so that 同一套部署配置具有可预期行为。
- Priority: P2
- Description: 云厂商、region、host 与 AK/SK 环境变量遵循 Python 参考实现的既有差异；显式有效配置仍高于环境变量。

Acceptance Requirements (EARS):

- REQ-005.1: When 未显式指定 cloud provider 时, the SDK shall 按 `AGENTKIT_CLOUD_PROVIDER`、`CLOUD_PROVIDER`、默认 `volcengine` 的顺序解析。
- REQ-005.2: When cloud provider 为 `volcengine` 时, the SDK shall 使用 `VOLCENGINE_ACCESS_KEY`、`VOLCENGINE_SECRET_KEY` 与可选 `VOLCENGINE_SESSION_TOKEN`，region 按有效显式值、`DATABASE_VIKING_REGION`、`REGION`、默认 `cn-beijing` 的顺序解析。
- REQ-005.3: When cloud provider 为 `byteplus` 时, the SDK shall 使用 `BYTEPLUS_ACCESS_KEY`、`BYTEPLUS_SECRET_KEY` 与可选 `BYTEPLUS_SESSION_TOKEN` 作为管理面凭据来源。
- REQ-005.4: When BytePlus Knowledgebase region 缺失或为 `cn-beijing`、`cn-shanghai`、`cn-guangzhou` 时, the SDK shall 使用 `cn-hongkong`；其他有效显式 region 保持不变。
- REQ-005.5: When BytePlus Memory 初始化时, the SDK shall 使用 `cn-hongkong` 及默认 host `api-knowledgebase.mlp.cn-hongkong.bytepluses.com`。
- REQ-005.6: When 未显式配置 host / base URL 时, the SDK shall 为 Volcengine 使用 `api-knowledgebase.mlp.<region>.volces.com`，为 BytePlus Knowledgebase 使用 `api-knowledgebase.mlp.<effective-region>.bytepluses.com`。
- REQ-005.7: When 用户提供对应能力支持的有效显式 host / base URL 时, the SDK shall 优先于环境变量和默认 host 使用该值；环境变量名称与 Python 行为对齐，其中 Knowledgebase 为 `DATABASE_VIKING_BASE_URL`，Memory 为 `DATABASE_VIKINGMEM_BASE_URL`。

Gherkin:

```gherkin
Scenario: BytePlus Memory 使用固定香港区域默认端点
  Given cloud provider 为 byteplus
  And 用户未显式配置 Memory host
  When SDK 初始化 Viking Memory
  Then effective region 为 cn-hongkong
  And effective host 为 api-knowledgebase.mlp.cn-hongkong.bytepluses.com
```

### REQ-006: 失败反馈与凭据安全

- User Story: As a SDK 使用者与运维人员, I want 鉴权问题可定位且凭据不泄露, so that 我能安全排障。
- Priority: P2
- Description: 新鉴权路径保持现有接口的返回/异常风格，同时提供不含凭据的鉴权方式、操作和远端错误上下文。

Acceptance Requirements (EARS):

- REQ-006.1: If API Key 无效、过期或无权限, then the SDK shall 保留可定位的鉴权失败信息，不得把失败标记为成功；查询接口的空结果/异常表现可保持其已有公开语义。
- REQ-006.2: If 网络失败、远端服务失败或响应格式异常, then the SDK shall 保持对应接口既有失败风格，并保留操作类型与非敏感远端错误信息。
- REQ-006.3: The SDK shall 不在日志、异常文本、Trace、Metric、测试输出、README 或示例中记录完整 API Key、AK、SK、session token 或 `Authorization` 值。
- REQ-006.4: When 记录鉴权选择时, the SDK shall 最多记录所选鉴权类型、provider、region、host 与操作类型等非敏感信息。

### REQ-007: 使用文档

- User Story: As a Java SDK 使用者, I want 从仓库文档了解 API Key 配置与边界, so that 我可以正确迁移或保持原有配置。
- Priority: P2

Acceptance Requirements (EARS):

- REQ-007.1: The repository documentation shall 说明 Knowledgebase 与 Memory 的 API Key 环境变量、显式配置入口、优先级、AK/SK fallback 和 API Key-only 前置条件。
- REQ-007.2: The repository documentation shall 说明 API Key 覆盖的三类数据面操作，以及集合管理和 Knowledgebase 文档写入仍需 AK/SK。
- REQ-007.3: The repository documentation shall 使用占位符展示配置，不得包含真实或可用凭据。

## 5. Backend Behavior

### 5.1 Operation and authentication matrix

| 能力 | API Key 存在 | API Key 不存在 | API Key-only 可用 | 本次行为 |
|---|---|---|---|---|
| Knowledgebase search | API Key | AK/SK | 是，集合须已存在 | 新增 API Key 路径 |
| Memory search | API Key | AK/SK | 是，集合须已存在 | 新增 API Key 路径 |
| Memory add session | API Key | AK/SK | 是，集合须已存在 | 新增 API Key 路径 |
| Knowledgebase / Memory 集合检查与创建 | AK/SK | AK/SK | 否；初始化时跳过 | 保持管理面鉴权 |
| Knowledgebase 文档上传与 `addDoc` | AK/SK | AK/SK | 否 | 保持现状 |

### 5.2 Configuration state rules

| API Key | 完整 AK/SK | 初始化行为 | 数据面行为 | 管理面行为 |
|---|---|---|---|---|
| 有效 | 有效 | 可执行既有管理面 precheck | API Key | AK/SK |
| 有效 | 缺失或不完整 | 跳过管理面 precheck / 自动创建 | API Key | 不可用 |
| 无效或缺失 | 有效 | 保持现状 | AK/SK | AK/SK |
| 无效或缺失 | 缺失或不完整 | 返回明确配置缺失反馈 | 不可用 | 不可用 |

### 5.3 Unchanged behavior

- 集合命名校验、project、resource ID、query、topK、metadata、rerank、chunk diffusion、memory type 与用户过滤规则保持现有语义。
- 成功结果对象、空结果和异步封装的公开语义保持兼容。
- API Key 是可选增强；不得迫使现有 AK/SK 用户修改调用方式。

## 6. SDK Configuration Contract

本节定义对 SDK 使用者可观察的配置契约，不约束具体 Java 类或构造方式。

| 配置语义 | 显式配置 | 环境变量 | 默认 / fallback | 适用范围 |
|---|---|---|---|---|
| Knowledgebase API Key | 可选 `apiKey` | `DATABASE_VIKING_API_KEY` | 无；缺失时用 AK/SK | Knowledgebase search |
| Memory API Key | 可选 `apiKey` | `DATABASE_VIKINGMEM_API_KEY` | 无；缺失时用 AK/SK | Memory search / add session |
| Cloud provider | 可选 provider | `AGENTKIT_CLOUD_PROVIDER` > `CLOUD_PROVIDER` | `volcengine` | Viking |
| Region | 可选 region | `DATABASE_VIKING_REGION` > `REGION` | Volcengine 为 `cn-beijing`；BytePlus 见 REQ-005 | Viking |
| Knowledgebase base URL | 可选 base URL | `DATABASE_VIKING_BASE_URL` | 按 provider 与 effective region 推导 | Knowledgebase |
| Memory base URL | 可选 base URL | `DATABASE_VIKINGMEM_BASE_URL` | 按 provider 与 effective region 推导 | Memory |
| Volcengine 管理面凭据 | 可选 AK/SK/session token | `VOLCENGINE_ACCESS_KEY` / `VOLCENGINE_SECRET_KEY` / `VOLCENGINE_SESSION_TOKEN` | 无 | 管理面与 API Key fallback |
| BytePlus 管理面凭据 | 可选 AK/SK/session token | `BYTEPLUS_ACCESS_KEY` / `BYTEPLUS_SECRET_KEY` / `BYTEPLUS_SESSION_TOKEN` | 无 | 管理面与 API Key fallback |

- 对同一配置语义，优先级统一为“有效显式配置 > 对应环境变量 > 文档声明的默认值”。
- API Key 不进入请求 body 或 query；API Key 数据面请求使用 Bearer Authorization。
- 本次不改变 Viking 服务端请求/响应字段，也不引入新的 OpenAPI / RPC 版本。

## 7. NFR / DFX

### 7.1 Compatibility

- API Key 未配置时，现有 AK/SK 用户的源码调用方式和运行行为保持兼容。
- 新增显式配置入口应为可选能力，不得使现有构造或 Builder 调用失效。
- 不涉及数据迁移；回退到旧版本后，仍可继续通过 AK/SK 使用既有能力。

### 7.2 Security

- API Key、AK/SK、session token 和完整 Authorization 均按 Secret 处理。
- 自动化测试使用不可用的假值；真实凭据只允许由受控测试环境注入。
- 错误反馈须支持定位配置项、鉴权模式和失败操作，但不得回显 Secret。

### 7.3 Reliability and diagnosability

- 不因新增 API Key 路径改变现有请求的超时、重试或幂等语义；若底层依赖行为不同，技术设计需显式说明兼容影响。
- 数据面失败不得触发隐式集合创建或在 API Key 与 AK/SK 之间对同一失败请求自动切换，以免掩盖权限配置错误。
- 无需新增业务 Metrics 或 Dashboard；应复用既有日志/异常链路记录非敏感诊断信息。

### 7.4 Release and rollback

- API Key 路径由是否存在有效 API Key 自然启用，不要求新增产品 Feature Gate。
- 发布验证至少覆盖 API Key-only、API Key + AK/SK、AK/SK-only、全凭据缺失及 Volcengine / BytePlus 两类环境。
- 若 API Key 路径需回退，用户可移除 API Key 恢复 AK/SK 路径；版本回滚不涉及数据回填或 schema 回滚。

## 8. Acceptance Scenarios

| ID | 场景 | 验收结果 |
|---|---|---|
| AC-001 | 仅显式 Knowledgebase API Key，集合已存在 | search 使用显式 API Key 成功返回，且不读取 AK/SK |
| AC-002 | Knowledgebase 显式 API Key 与环境变量值不同 | 使用显式值 |
| AC-003 | Knowledgebase 显式 API Key 为空，环境变量有效 | 回退环境变量值 |
| AC-004 | 仅 Memory API Key，集合已存在 | search 与 add session 均成功，且不要求 AK/SK |
| AC-005 | API Key 与完整 AK/SK 并存 | 三类数据面操作使用 API Key，管理面与知识入库使用 AK/SK |
| AC-006 | 仅 API Key 初始化 | 跳过集合检查与自动创建；不存在的集合在实际请求处明确失败 |
| AC-007 | 无 API Key、有 AK/SK | Viking 现有查询、写入及管理行为保持不变 |
| AC-008 | API Key 与 AK/SK 均不可用 | 首次需要远端鉴权前得到明确配置缺失反馈 |
| AC-009 | 无效或无权限 API Key | 返回或记录不含 Secret 的可诊断失败，不标记为成功 |
| AC-010 | BytePlus 默认配置 | provider、region、host 与 BytePlus 凭据环境变量按 REQ-005 生效 |
| AC-011 | Volcengine 自定义 region | 显式值高于 `DATABASE_VIKING_REGION`，后者高于 `REGION` |
| AC-012 | 安全回归 | 日志、异常、Trace、Metric 与文档示例均不含完整凭据或 Authorization 值 |
| AC-013 | 文档回归 | 中英文 README 或等价使用文档覆盖配置、优先级、能力边界与 fallback |

### 8.1 Verification scope

- 单元验证覆盖配置来源与空值清洗、鉴权选择、Bearer header、AK/SK fallback、API Key-only 初始化、管理面分流、region/host 差异及 Secret 不泄露。
- 仓库集成验证使用 mock/fake transport 覆盖请求契约；默认 CI 不依赖真实密钥。
- E2E 使用受控测试凭据和预创建集合分别验证 Knowledgebase search、Memory add session 后 search，以及至少一个鉴权失败场景；若环境无法提供 BytePlus 凭据，应保留 BytePlus 端点与配置解析的自动化验证，并将真实连通性列为人工补测项。

## 9. UX Design

本需求不涉及前端 UX、页面、交互或用户可见产品文案变更；仅更新 SDK 使用文档。

## 10. Data Model and Storage

本需求不涉及本地数据模型、数据库 schema、迁移、回填、保留策略或存储生命周期变更。

## 11. OpenAPI / RPC

本需求不新增或修改服务端 OpenAPI、RPC、Webhook、CLI；变化仅属于 Java SDK 配置与既有 Viking 请求的鉴权方式。

## 12. Metrics

本需求不新增或变更埋点、业务指标、Dashboard 或 Metric label；鉴权类型不得携带凭据值。

## 13. Open Questions

无。已确认数据面范围、环境变量、API Key-only 行为、混合鉴权分流和 BytePlus 兼容要求。

## 14. Traceability

| REQ ID | 需求来源 / 证据 | 主要验收 | 下游关注 |
|---|---|---|---|
| REQ-001 | 原始需求配置优先级；Python API Key 清洗测试 | AC-001 ~ AC-004 | 配置解析与单测 |
| REQ-002 | 已确认 Knowledgebase 数据面范围；Python search Bearer 实现 | AC-001 ~ AC-003 | Knowledgebase 技术设计与 E2E |
| REQ-003 | 已确认 Memory search / add session 范围；Python SDK auth 实现 | AC-004 | Memory 技术设计与 E2E |
| REQ-004 | 已确认管理面边界和 API Key-only 行为 | AC-005 ~ AC-008 | 初始化、鉴权分流与兼容回归 |
| REQ-005 | 已确认 BytePlus 对齐；Python region/host 测试 | AC-010、AC-011 | 多 provider 配置与端点验证 |
| REQ-006 | 安全技术评审标记；公共配置与脱敏规范 | AC-009、AC-012 | 错误语义、安全测试与 Review |
| REQ-007 | 已确认 README 更新要求 | AC-013 | 中英文文档与示例 |
