# E2E 测试设计：veadk-java Viking 数据面支持 API Key 请求

## 前置条件
- 环境 / lane / profile：使用仓库现有 Java 17、Maven Surefire 3.5.2 与 JUnit 5 Runner，在 `core` 模块执行仓库级组件链 E2E；正式执行入口为 `mvn -pl core -Dtest=VikingApiKeyE2ETest,VikingApiKeyConfigTest,VikingApiKeyDocumentationTest test`。本设计节点不执行测试。测试通过内存 fake transport / fake SDK client 贯通公共配置、鉴权选择、数据面请求与公共结果映射，不依赖真实 Viking 环境。
- 测试环境变量配置：自动化用例无需新增共享环境变量或真实凭证。使用 JUnit Pioneer 在单个用例作用域内设置或清除 `DATABASE_VIKING_API_KEY`、`DATABASE_VIKINGMEM_API_KEY`、`VOLCENGINE_ACCESS_KEY`、`VOLCENGINE_SECRET_KEY` 与 `MODEL_AGENT_API_KEY`；值为运行时生成的非真实哨兵，禁止进入用例显示名、断言消息、日志和报告。执行前确认环境变量扩展仍由 `core/pom.xml` 的 JUnit Pioneer 1.7.1 提供，执行后由扩展自动回读并恢复原环境。
- 依赖数据与状态：collection、project、resource ID、query、消息、metadata、过滤条件与响应体均由 fixture/builder 在内存中按 Case 创建；fake transport / client 记录目标端点、鉴权模式、调用次数和脱敏后的业务字段，并返回成功、空结果或结构化失败。凭证比较使用不回显输入的 predicate / 自定义断言，失败消息只能给出鉴权模式与布尔结果。
- 资源复用与回收：复用 `core/src/test/java`、Surefire 的 `**/*Test.java` 发现规则、Mockito inline/Jupiter、JUnit Pioneer 及既有 Viking 测试构造方式；新增公共 fixture 时放在 `core/src/test/java` 的 Viking 测试包内。每个 Case 在 `afterEach` 关闭本地 fake server / executor、清空捕获请求和日志、恢复环境变量，不申请云端 collection，不写共享可变状态。

## 已有用例分析
| Case ID / 入口 | 文件路径 | 前置与数据 | 实际调用 | 关键断言 | Runner | 用例状态 | 协议兼容性 | 覆盖状态 | 证据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `builderCreatesVikingBackend` | `core/src/test/java/com/volcengine/veadk/knowledgebase/KnowledgeBaseTest.java` | 静态 mock AK/SK，mock wrapper 返回既有 collection 与一条结果 | `KnowledgeBase.builder().backend("viking").appName(...).topK(4).build().search(...)` | 公共 builder 能创建 Viking backend，搜索内容可返回 | Maven Surefire + JUnit 5 | 正常 | API Key 公共配置入口未知，当前仅兼容 AK/SK | PARTIAL | 当前文件 88-111 行；`KnowledgeBase.Builder` 当前无 API Key / resource ID 配置入口 |
| `search_convertsVikingEntriesToCommonEntries`、`search_blankQuery_returnsEmptyListWithoutCallingWrapper`、`addDoc_delegatesToWrapper` | `core/src/test/java/com/volcengine/veadk/knowledgebase/backends/viking/VikingKnowledgebaseBackendTest.java` | 注入 mock `VikingKnowledgebaseWrapper` | backend 搜索、空 query、`addDoc` | content/metadata 映射、topK/rerank/chunk 参数、空 query 不发请求、addDoc 委托 | Maven Surefire + JUnit 5 + Mockito | 正常 | 数据语义可复用；未覆盖 API Key、Bearer、资源定位和管理边界 | PARTIAL | 当前文件 78-116 行；backend 当前 33-55 行只用 AK/SK 且总执行 `ensureCollection()` |
| `searchKnowledge_success`、`searchKnowledge_empty` | `core/src/test/java/com/volcengine/veadk/integration/vikingknowledgebase/VikingKnowledgebaseWrapperTest.java` | spy AK/SK wrapper，stub `RawResponse` | `searchKnowledge(...)` | 成功结果映射；失败当前被断言为空列表 | Maven Surefire + JUnit 5 + Mockito | 正常 | API Key HTTP 路径未知；失败吞为空结果与 REQ-005 不兼容 | STALE | 当前文件 105-133 行；失败用例把 `SdkError.EHTTP` 当作空结果 |
| `constructor_*`、`addSessionToMemory_*`、`searchMemory_returnsResponseWithEntries_and_callsWrapperWithExpectedArgs` | `core/src/test/java/com/volcengine/veadk/memory/viking/VikingMemoryServiceTest.java` | 静态 mock AK/SK 与 memory type，构造 mock wrapper | collection 检查/创建、session 添加、memory 检索 | 管理预检查、消息过滤、metadata、异常传播、topK / user / memory type 参数 | Maven Surefire + JUnit 5 + Mockito | 正常 | 业务数据语义可复用；API key-only 初始化与 API Key client 未覆盖 | PARTIAL | 当前文件 42-93、95-233、237-281 行；service 当前 42-56 行总取 AK/SK 并管理 collection |
| `addSession_true/false`、`searchMemory_success/empty_on_error/empty_when_missing_result_list` | `core/src/test/java/com/volcengine/veadk/integration/vikingmemory/VikingMemoryWrapperTest.java` | spy AK/SK wrapper，stub `RawResponse` | `addSession(...)`、`searchMemory(...)` | 请求映射和成功结果；失败当前返回 false / 空列表 | Maven Surefire + JUnit 5 + Mockito | 正常 | API Key SDK/client 路径未知；失败吞并与 REQ-005 不兼容 | STALE | 当前文件 93-169 行；错误用例未区分鉴权、资源、网络和正常空结果 |
| `getAccessKey`、`getSecretKey` 及缺失变量用例 | `core/src/test/java/com/volcengine/veadk/utils/EnvUtilTest.java` | JUnit Pioneer set / clear 环境变量 | `EnvUtil` getter | 环境读取和缺失异常 | Maven Surefire + JUnit 5 + JUnit Pioneer | 正常 | Runner 与 fixture 兼容；两个 Viking API Key getter、空值规范化与双 Key 隔离未覆盖 | NOT_COVERED | 当前文件 24-46 行；`EnvUtil` 当前仅声明 AK/SK 与 memory type，无两个 API Key 变量 |
| 中英文 Viking 配置说明 | `README.md`、`README_zh.md` | 静态文档 | 读取 Viking 环境配置段 | 当前只列 AK/SK | 文档静态断言（待新增） | 正常 | 缺少新增 API Key 契约 | NOT_COVERED | `README.md` 92-100 行、`README_zh.md` 93-100 行 |

## 测试用例总数：11

## 用例清单
### TC-01 Knowledgebase 与 Memory API Key 配置解析、优先级和隔离
- 动作：ADD
- 用例描述：参数化验证两个能力各自的有效显式值、对应环境变量、无效显式值回退、无效环境值以及跨能力隔离，闭环 REQ-003.1～REQ-003.4。
- 已有用例映射：无完整 Case；复用 `EnvUtilTest` 的 JUnit Pioneer 环境变量隔离方式，建议新增 `core/src/test/java/com/volcengine/veadk/e2e/viking/VikingApiKeyConfigTest.java`。
- 前置条件与测试数据：按 Knowledgebase / Memory 两类能力参数化；显式值依次取有效值、`null`、空字符串、纯空白、大小写混合的 `none` / `null`，对应环境变量取有效或无效值；为两个能力与 `MODEL_AGENT_API_KEY` 分别生成不相等的内存哨兵。
- 资源复用与回收：复用 JUnit Pioneer；无新增云资源，逐例恢复所有环境变量与参数对象。
- 输入：显式 `apiKey` 语义配置、`DATABASE_VIKING_API_KEY`、`DATABASE_VIKINGMEM_API_KEY`、`MODEL_AGENT_API_KEY` 组合。
- 期望输出：有效显式值优先；无效显式值读取对应环境变量；对应环境也无效时解析为 API Key 未配置；Knowledgebase 与 Memory 不互用 Key，均不读取模型 Key。
- 执行步骤：
  1. 为当前能力设置参数化显式值和对应/非对应环境变量，构造配置或 service。
  2. 通过可注入的 fake client factory 捕获最终鉴权模式与选中来源，不记录凭证内容。
  3. 断言来源、是否配置和跨能力调用次数，销毁实例并恢复环境。
- 断言：仅比较来源枚举、鉴权模式、调用计数与不回显的值匹配 predicate；显式有效时环境 getter 不影响结果；无效显式值全部回退；两个 API Key 与模型 Key 的 getter / factory 调用保持隔离。
- 异常处理：任一参数组合构造失败时保留组合标签但不得包含值；发现环境污染或跨能力读取立即 fail，清理失败标为 blocked 并给出变量名，不给出变量值。
- Runner 与清理：Maven Surefire + JUnit 5 parameterized / JUnit Pioneer；禁止并行共享环境状态，扩展在用例结束恢复环境。
- 测试点：REQ-003.1、REQ-003.2、REQ-003.3、REQ-003.4；有效显式值 > 对应环境变量，空值规范化和双 Key 隔离。

### TC-02 API key-only Knowledgebase 搜索组件链
- 动作：READAPT
- 用例描述：从公共 `KnowledgeBase` / Viking 配置入口贯通到搜索 transport，验证只配置 Knowledgebase API Key 时跳过管理预检查、使用 Bearer 语义搜索已有 collection，并保持请求字段与公共结果映射。
- 已有用例映射：`KnowledgeBaseTest#builderCreatesVikingBackend`、`VikingKnowledgebaseBackendTest#search_convertsVikingEntriesToCommonEntries` 和 `VikingKnowledgebaseWrapperTest#searchKnowledge_success`；建议在 `VikingApiKeyE2ETest` 重新适配为单条组件链 Case。
- 前置条件与测试数据：清除 AK/SK 与 Memory API Key；显式配置 Knowledgebase API Key、collection、project、可选 resource ID、query、topK、filter、rerank 和 chunk diffusion；fake transport 返回一条含 content 与 metadata 的成功响应。
- 资源复用与回收：复用现有 builder、backend 映射和 wrapper 请求模型；本地 transport 每例新建并关闭，无新增云资源。
- 输入：已有 collection 的搜索请求及测试内确定的资源定位和业务参数。
- 期望输出：仅发生一次搜索数据面请求；目标为 Volcengine Viking Knowledgebase 端点；鉴权 scheme 为 Bearer 且值匹配选中的内存哨兵；不发生 collection info / create；返回 `KnowledgebaseEntry` 的 content、metadata、数量与 AK/SK 路径语义一致。
- 执行步骤：
  1. 清除 AK/SK，建立只含有效 Knowledgebase API Key 的公共配置并注入 capture transport。
  2. 通过公共入口构造 Knowledgebase，搜索已有 collection。
  3. 校验捕获请求的端点、鉴权模式、project / resource ID、collection、query、topK、filter、rerank / chunk diffusion 与返回映射后关闭资源。
- 断言：管理调用计数为 0；搜索调用计数为 1；Authorization 只验证 scheme 和不回显 predicate；资源定位与业务字段完整；返回公共类型、content 和 metadata 精确匹配 fixture。
- 异常处理：若构造阶段触发 AK/SK getter 或管理请求立即 fail；fake transport 超时或未收到请求按 fail 处理并只输出 endpoint / operation；清理失败标为 blocked。
- Runner 与清理：Maven Surefire + JUnit 5 + Mockito / 轻量本地 fake transport；`afterEach` 关闭 transport、清空捕获内容并恢复环境。
- 测试点：REQ-001.1、REQ-001.2、REQ-002 范围隔离、REQ-004.4、REQ-005.5；API key-only 搜索与初始化管理边界。

### TC-03 API key-only Memory 添加与检索组件链
- 动作：READAPT
- 用例描述：通过 `VikingMemoryService` 公共能力在同一实例中添加有效用户消息并检索，验证 Memory API Key client、消息/metadata/filter 映射、公共响应类型及 API key-only 跳过管理预检查。
- 已有用例映射：`VikingMemoryServiceTest#addSessionToMemory_withValidMessages_callsAddSession_and_buildsMetadata`、`#searchMemory_returnsResponseWithEntries_and_callsWrapperWithExpectedArgs`，以及 `VikingMemoryWrapperTest#addSession_true/#searchMemory_success`；建议重新适配进 `VikingApiKeyE2ETest`。
- 前置条件与测试数据：清除 AK/SK 与 Knowledgebase API Key；显式配置 Memory API Key、已有 collection、project、用户、memory type 与 topK；session 含两条有效 user 文本和不会写入的非 user 事件；fake API Key client 为添加返回成功并为搜索返回两条 memory。
- 资源复用与回收：复用现有 `Session` / `Event` fixture、消息筛选、`Metadata` 与 `SearchMemoryResponse` 映射；fake client 每例销毁，无新增云资源。
- 输入：`addSessionToMemory(session)` 后调用 `searchMemory(appName, userId, query)`。
- 期望输出：添加和检索各调用一次 API Key 数据面 client，不调用 collection info / create；添加 Completable 正常完成；检索返回现有 `SearchMemoryResponse` / `MemoryEntry` 类型；collection、project、消息、metadata、user filter、memory type、query 与 limit 保持现有语义。
- 执行步骤：
  1. 构造 API key-only Memory service 与捕获型 fake client，确认初始化完成。
  2. 添加 session 并等待既有异步边界完成，再执行 memory 搜索。
  3. 校验两次数据面请求、无管理调用、业务字段及公共返回映射，关闭 client。
- 断言：管理调用计数为 0；add/search 调用各 1；两次均使用 Memory API Key 模式且不输出值；仅有效 user 消息被写入；metadata/user/memory type/topK 精确匹配；公共结果包含 fixture 摘要。
- 异常处理：异步添加超时、错误完成或额外管理调用均 fail；证据仅记录操作、状态和调用次数；关闭 client 失败标为 blocked。
- Runner 与清理：Maven Surefire + JUnit 5、RxJava TestObserver / blocking 边界、Mockito fake client；结束后释放异步资源并恢复环境。
- 测试点：REQ-002.1、REQ-002.2、REQ-002.4、REQ-003.4、REQ-005.5；Memory API Key 添加/检索与管理隔离。

### TC-04 Memory 无有效消息保持无请求成功
- 动作：MODIFY
- 用例描述：把既有空消息 Case 改为 API key-only 初始化，确认 assistant 事件、缺 content / parts / text 的 user 事件不会触发添加请求，同时初始化也不触发管理请求。
- 已有用例映射：`VikingMemoryServiceTest#addSessionToMemory_noValidMessages_shouldComplete_and_notInvokeAddSession`；保留测试意图，修改鉴权输入与管理断言。
- 前置条件与测试数据：仅配置 Memory API Key；session 包含现有过滤规则下全部无效的事件集合；注入 capture fake client。
- 资源复用与回收：复用现有 Event / Session mock；无新增资源。
- 输入：对无有效用户文本的 session 调用 `addSessionToMemory`。
- 期望输出：Completable 正常完成；添加、检索和 collection 管理调用均为 0。
- 执行步骤：
  1. 构造 API key-only service 和无有效消息 session。
  2. 订阅添加结果直至完成。
  3. 校验全部外部调用计数为 0 并清理环境。
- 断言：异步结果 complete 且无 error；fake client 的 add/search/manage 均未调用。
- 异常处理：任何外部请求或异步 error 都 fail；测试超时只记录操作名，不打印 session 内容。
- Runner 与清理：Maven Surefire + JUnit 5 + RxJava TestObserver + Mockito；逐例恢复环境和 mocks。
- 测试点：REQ-002.3、REQ-002.4；既有消息过滤语义在 API Key 路径不回归。

### TC-05 API Key 数据面正常空结果
- 动作：READAPT
- 用例描述：对 Knowledgebase 搜索和 Memory 检索参数化返回协议定义的成功空结果，验证空集合仍是公共成功语义，且与失败路径严格区分。
- 已有用例映射：`VikingKnowledgebaseWrapperTest#searchKnowledge_empty` 当前实际模拟 HTTP 失败，语义过期；`VikingMemoryWrapperTest#searchMemory_empty_when_missing_result_list` 可复用正常空结果意图，需接入 API Key client。
- 前置条件与测试数据：分别构造两个 API key-only 实例；fake transport / client 返回成功状态及空 `result_list`（或协议等价空结构）。
- 资源复用与回收：复用现有空集合映射；每组关闭 fake client，不申请资源。
- 输入：非空 Knowledgebase query 和 Memory query。
- 期望输出：Knowledgebase 返回空 `List<KnowledgebaseEntry>`；Memory 返回 memories 为空的 `SearchMemoryResponse`；均无异常、无降级、无管理调用。
- 执行步骤：
  1. 为两个能力分别准备成功空响应 fixture 和 API key-only 实例。
  2. 调用对应搜索入口。
  3. 校验成功状态、空公共结果和单次 API Key 请求，清理 fixture。
- 断言：结果对象非 null 且集合为空；错误分类器未调用；AK/SK client / manager 未调用。
- 异常处理：解析成功空响应时抛错或把空结果标为鉴权失败即 fail；只记录能力与响应结构标签。
- Runner 与清理：Maven Surefire + JUnit 5 parameterized fake response；用例结束清空响应字节与捕获请求。
- 测试点：REQ-001.3、REQ-002.2、REQ-005.2、REQ-005.3；正常空结果与失败分离。

### TC-06 仅 AK/SK、双凭证共存与公共 API 向后兼容
- 动作：MODIFY
- 用例描述：参数化验证无有效 API Key 时既有 AK/SK 数据面和管理行为不变；API Key 与 AK/SK 共存时数据面用 API Key、管理操作用 AK/SK；既有无 API Key 构造和 builder 仍可调用。
- 已有用例映射：`KnowledgeBaseTest#builderCreatesVikingBackend`、两个 backend/service 的 `constructor_*` 与 `addDoc_delegatesToWrapper`；保留意图并增加凭证路由断言。
- 前置条件与测试数据：组合 A 为两种 API Key 均未设置且 AK/SK 成对有效；组合 B 为对应 API Key 与 AK/SK 共存；组合 C 为显式无效、环境无效且 AK/SK 成对有效。使用独立 capture 数据面 client 与管理 client。
- 资源复用与回收：复用现有构造、collection exists/create、addDoc 和搜索 fixture；无新增资源。
- 输入：Knowledgebase 搜索、Memory 添加/检索，以及 Knowledgebase `addDoc` / collection 管理边界调用。
- 期望输出：组合 A/C 全部保持 AK/SK 路径且不报 API Key 缺失；组合 B 的三类数据面调用使用对应 API Key，管理预检查或 `addDoc` 只使用 AK/SK；原公共构造签名和 builder 行为可发现。
- 执行步骤：
  1. 分别构造 AK/SK-only、无效 API Key + AK/SK、双凭证实例。
  2. 调用对应数据面与合法管理入口并捕获 client 路由。
  3. 校验鉴权模式、管理/数据面调用矩阵、公共结果和旧构造可用性后清理。
- 断言：路由与 Spec 鉴权矩阵逐格一致；双凭证时 API Key 不进入管理 client，AK/SK 不用于同一次数据面调用；AK/SK-only 的 collection 检查/创建与返回映射保持既有断言；旧签名可编译并运行。
- 异常处理：发现凭证混用、数据面双发、旧入口不可发现或无效 API Key 阻断 AK/SK 时 fail；证据仅含模式和方法签名。
- Runner 与清理：Maven Surefire + JUnit 5 + Mockito construction/factory capture；关闭 clients，恢复四个鉴权环境变量。
- 测试点：REQ-001.4、REQ-003.3、REQ-004.1、REQ-004.2、REQ-004.4；AK/SK 兼容、双凭证路由和管理面边界。

### TC-07 两类凭证均未配置时的安全失败
- 动作：ADD
- 用例描述：对 Knowledgebase 与 Memory 参数化验证 API Key 本地未配置有效值且 AK/SK 不成对时，在首个外部请求前报告能力明确的凭证缺失，并且异常不含敏感值。
- 已有用例映射：无；现有 `EnvUtilTest` 只分别验证单个 AK/SK 环境变量缺失，未覆盖鉴权选择边界。建议加入 `VikingApiKeyE2ETest`。
- 前置条件与测试数据：API Key 取缺失或各类无效值；AK/SK 取均缺失、仅 AK、仅 SK；fake client factory 记录是否构造或请求。
- 资源复用与回收：复用 JUnit Pioneer 与能力参数化 fixture；无新增资源。
- 输入：构造实例并触发对应数据面入口。
- 期望输出：抛出可识别 Knowledgebase / Memory 所需配置来源的凭证缺失异常；未构造可发请求的 client或外部请求数为 0；消息、cause 和捕获日志不含任何哨兵。
- 执行步骤：
  1. 设置无有效 API Key 与不完整 AK/SK 组合。
  2. 构造并调用对应能力，捕获异常和日志。
  3. 对异常分类、配置名、请求计数与脱敏结果断言，清理环境。
- 断言：错误类型 / code 或稳定分类可区分能力；只出现允许的配置项名称，不出现值；数据面与管理请求均为 0。
- 异常处理：若发出任何请求、返回空成功或异常含哨兵即 fail；日志捕获不可用时不得降级为仅人工观察，应 blocked 并补齐仓内 capture fixture。
- Runner 与清理：Maven Surefire + JUnit 5 + JUnit Pioneer + 本地日志 capture；每例关闭 appender 并恢复环境。
- 测试点：REQ-004.3、REQ-005.1、REQ-005.5；凭证缺失时机、能力可诊断性与不泄密。

### TC-08 已选 API Key 失败不降级且错误语义可区分
- 动作：READAPT
- 用例描述：对两种数据面和失败类型参数化注入鉴权/过期/权限、资源不存在、网络/超时、服务端和解析失败，验证异常向公共边界传播、不吞为空结果、不切换到环境 API Key 或 AK/SK、不自动创建资源。
- 已有用例映射：`VikingKnowledgebaseWrapperTest#searchKnowledge_empty`、`VikingMemoryWrapperTest#addSession_false/#searchMemory_empty_on_error` 与 `VikingMemoryServiceTest#addSessionToMemory_whenWrapperThrows_shouldPropagateRuntimeException`；错误传播意图可复用，但 wrapper 失败吞并行为需 READAPT。
- 前置条件与测试数据：显式有效 API Key、另一个不同的对应环境 API Key 与有效 AK/SK 同时存在；fake transport / client 按失败矩阵返回不含敏感信息的结构化错误或抛出异常。
- 资源复用与回收：复用 wrapper/service 异常 fixture，扩展统一 failure matrix；无云资源。
- 输入：Knowledgebase search、Memory add 与 Memory search 的失败请求。
- 期望输出：显式 API Key 只尝试一次；调用方收到可区分的鉴权/权限、资源、网络/超时、服务端或解析失败；搜索不返回空成功，Memory 添加不返回普通完成；不调用环境 Key、AK/SK client、collection info/create。
- 执行步骤：
  1. 为每个能力/操作/失败类型配置响应，创建双凭证实例。
  2. 调用数据面并捕获公共异常 / RxJava error。
  3. 校验错误分类、单次调用、无 fallback、无管理副作用与脱敏后关闭 fixture。
- 断言：错误分类与失败矩阵一致；API Key transport 调用 1 次，其他鉴权 client 与管理调用 0 次；异常和日志无哨兵；正常空结果 fixture 不参与本 Case。
- 异常处理：fake client 自身未按矩阵响应时标记 fixture fail；超时使用有界条件等待，禁止固定 sleep；清理失败标 blocked。
- Runner 与清理：Maven Surefire + JUnit 5 parameterized / dynamic tests、RxJava TestObserver、Mockito fake client；每例重建 client 与日志 capture。
- 测试点：REQ-003.5、REQ-005.2、REQ-005.3、REQ-005.4；失败可诊断、不降级、不创建 collection。

### TC-09 Secret 防泄露与目标端点隔离
- 动作：ADD
- 用例描述：对成功、凭证缺失及 TC-08 的全部失败类型扫描异常链、捕获日志、测试诊断对象和脱敏请求快照，验证 API Key、AK/SK 与完整 Authorization 不被输出；同时确认 API Key 仅发往对应 Volcengine Viking 数据面端点。
- 已有用例映射：无；当前 Viking wrapper 日志会记录请求体和异常，但没有 API Key 路径及统一安全断言。建议作为 `VikingApiKeyE2ETest` 的安全参数化 Case。
- 前置条件与测试数据：为两个 API Key、AK/SK 分别生成高熵但非真实的内存哨兵；transport 仅保留 scheme、目标 host/path、业务字段白名单和凭证匹配布尔值，原始 header 在断言后立即丢弃。
- 资源复用与回收：复用 TC-02、TC-03、TC-07、TC-08 fixture；日志 appender 与请求 recorder 每例清空。
- 输入：两类成功请求、凭证缺失、鉴权/权限、资源、网络/超时、服务端与解析失败。
- 期望输出：日志、异常链、断言描述和 recorder 快照均不含任何哨兵或完整 Authorization；Knowledgebase API Key 仅到 Knowledgebase 搜索端点，Memory API Key 仅到 Memory 添加/检索 client，管理、TOS、Ark、BytePlus 和其他 downstream 调用计数为 0。
- 执行步骤：
  1. 安装内存日志 capture 和脱敏 recorder，运行各场景。
  2. 在不格式化原始 header 的断言中校验 scheme、选中凭证匹配与端点白名单。
  3. 对日志/异常/快照做哨兵 contains 扫描，随后清空原始字节和所有 capture。
- 断言：四类哨兵均不出现在任何可观察文本；不得出现完整 Authorization；只允许非敏感 operation、auth mode、collection/project/resource ID 与错误分类；无非目标 downstream 调用。
- 异常处理：任何泄露视为安全 fail，失败报告只能给出泄露载体和字段类别，禁止回显匹配片段；若无法捕获实现使用的日志 facade，blocked 并补测试 capture，不能人工目测替代。
- Runner 与清理：Maven Surefire + JUnit 5 + 与实现日志绑定兼容的内存 appender / provider；finally 中卸载 capture、清空 byte buffer 并关闭 transport。
- 测试点：REQ-005.5、REQ-006.4、NFR 7.2、API Design 6.2；Secret 脱敏和端点/产品隔离。

### TC-10 公共结果字段与非鉴权业务语义回归
- 动作：MODIFY
- 用例描述：以同一业务 fixture 分别走 API Key 与 AK/SK 数据面路径，比较 Knowledgebase 和 Memory 的公共结果及关键请求业务字段，确认新增鉴权不改变 topK、filter、rerank、chunk diffusion、消息、metadata、用户过滤与 memory type。
- 已有用例映射：`VikingKnowledgebaseBackendTest#search_convertsVikingEntriesToCommonEntries`、`VikingMemoryServiceTest#addSessionToMemory_withValidMessages_callsAddSession_and_buildsMetadata`、`#searchMemory_returnsResponseWithEntries_and_callsWrapperWithExpectedArgs`；增加双路径等价断言。
- 前置条件与测试数据：为两种鉴权路径构造相同 collection、project、query、结果、session、user 与 memory type fixture；每条路径使用独立 recorder，凭证不进入比较对象。
- 资源复用与回收：复用既有 content/metadata/MemoryEntry builder 与 mock session；无新增资源。
- 输入：两条 Knowledgebase search、两条 Memory add 和两条 Memory search，仅鉴权配置不同。
- 期望输出：除鉴权与必要资源定位字段外，请求业务字段等价；公共返回类型、顺序、content/summary、metadata 与空值处理等价。
- 执行步骤：
  1. 创建相同业务 fixture 的 API Key 和 AK/SK 实例。
  2. 依次运行搜索、添加和检索并生成去凭证的规范化请求快照。
  3. 比较请求快照与公共结果对象，关闭所有实例。
- 断言：规范化请求体逐字段相等；KnowledgebaseEntry / SearchMemoryResponse 关键字段逐项相等；不存在额外调用或共享状态依赖。
- 异常处理：差异报告只给出业务字段路径和期望/实际的非敏感值；凭证字段必须在 diff 前剔除；任何异步超时按 fail。
- Runner 与清理：Maven Surefire + JUnit 5 + fixture builder / recorder；每组重建实例，finally 清理快照。
- 测试点：REQ-001.2、REQ-002.1、REQ-002.2、NG-005、NFR 7.1；API Key 与 AK/SK 数据语义等价。

### TC-11 中英文文档配置、边界与占位凭证一致性
- 动作：ADD
- 用例描述：静态读取 `README.md` 与 `README_zh.md` 的 Viking 配置段，验证两个环境变量、显式优先与空值回退、API key-only 管理预检查、管理仍需 AK/SK、已有 collection 前提和占位凭证约束均可发现且中英文对应。
- 已有用例映射：无；当前 README 只列 AK/SK。建议新增 `core/src/test/java/com/volcengine/veadk/e2e/viking/VikingApiKeyDocumentationTest.java` 并由现有 Surefire 自动发现。
- 前置条件与测试数据：从仓库根定位两个 README；只读取受控 Viking 段落；维护中英文同一组契约 token / heading 和禁止的疑似真实凭证模式，禁止把文档内容全文写进断言。
- 资源复用与回收：只读仓库文件，无资源申请与持久化。
- 输入：中英文 README 的 Viking API Key 说明和示例。
- 期望输出：两份文档都包含准确变量名和各自数据面范围，明确显式优先、无效/空值回退、已有 collection、API key-only 跳过管理预检查、管理操作依赖 AK/SK；示例只使用明显占位符且不含真实或疑似真实 Secret。
- 执行步骤：
  1. 定位并读取两份 README 的 Viking 段落。
  2. 对每项契约做 token / 受控短语断言并校验中英文项数一致。
  3. 对代码块和配置示例运行疑似凭证扫描，仅报告文件、行号和规则 ID。
- 断言：REQ-006.1～REQ-006.4 每项在两种语言中均有命中；变量名无拼写差异；Knowledgebase / Memory 范围不串用；示例扫描零命中。
- 异常处理：文件缺失、契约项缺失或扫描命中即 fail；安全扫描禁止打印命中文本；工作目录无法定位时使用 Maven multi-module 根属性而非固定绝对路径。
- Runner 与清理：Maven Surefire + JUnit 5；只读文件句柄由 try-with-resources 关闭，无其他清理。
- 测试点：REQ-006.1、REQ-006.2、REQ-006.3、REQ-006.4；用户文档可发现性与安全示例。

## 覆盖矩阵
| 需求 key-point | 自动化分类 | 覆盖用例 | 已有覆盖状态 | 用例动作 | 是否闭环 |
| --- | --- | --- | --- | --- | --- |
| REQ-001.1 API Key 搜索且不要求 AK/SK | AUTOMATABLE_IN_REPO | TC-02 | NOT_COVERED | READAPT | 是 |
| REQ-001.2 公共结果类型与字段语义一致 | AUTOMATABLE_IN_REPO | TC-02、TC-10 | PARTIAL | READAPT、MODIFY | 是 |
| REQ-001.3 正常空结果不是鉴权失败 | AUTOMATABLE_IN_REPO | TC-05 | STALE | READAPT | 是 |
| REQ-001.4 addDoc / collection 管理不使用 API Key | AUTOMATABLE_IN_REPO | TC-06 | PARTIAL | MODIFY | 是 |
| REQ-002.1 Memory API Key 添加有效 session | AUTOMATABLE_IN_REPO | TC-03、TC-10 | PARTIAL | READAPT、MODIFY | 是 |
| REQ-002.2 Memory API Key 检索及公共响应 | AUTOMATABLE_IN_REPO | TC-03、TC-05、TC-10 | PARTIAL | READAPT、MODIFY | 是 |
| REQ-002.3 无有效消息无请求且正常完成 | AUTOMATABLE_IN_REPO | TC-04 | PARTIAL | MODIFY | 是 |
| REQ-002.4 API key-only 不检查或创建 collection | AUTOMATABLE_IN_REPO | TC-03、TC-04 | NOT_COVERED | READAPT、MODIFY | 是 |
| REQ-003.1 有效显式值优先环境变量 | AUTOMATABLE_IN_REPO | TC-01 | NOT_COVERED | ADD | 是 |
| REQ-003.2 无效显式值回退对应环境变量 | AUTOMATABLE_IN_REPO | TC-01 | NOT_COVERED | ADD | 是 |
| REQ-003.3 环境值也无效时进入 AK/SK 路径 | AUTOMATABLE_IN_REPO | TC-01、TC-06、TC-07 | NOT_COVERED | ADD、MODIFY | 是 |
| REQ-003.4 两个 API Key 独立使用 | AUTOMATABLE_IN_REPO | TC-01、TC-03 | NOT_COVERED | ADD、READAPT | 是 |
| REQ-003.5 已选 API Key 失败不降级 | AUTOMATABLE_IN_REPO | TC-08 | NOT_COVERED | READAPT | 是 |
| REQ-004.1 未配置 API Key 时保持 AK/SK | AUTOMATABLE_IN_REPO | TC-06 | COVERED | MODIFY | 是 |
| REQ-004.2 双凭证时数据面 API Key、管理面 AK/SK | AUTOMATABLE_IN_REPO | TC-06 | NOT_COVERED | MODIFY | 是 |
| REQ-004.3 两类凭证均无效时明确失败 | AUTOMATABLE_IN_REPO | TC-07 | NOT_COVERED | ADD | 是 |
| REQ-004.4 既有公共构造和调用仍可用 | AUTOMATABLE_IN_REPO | TC-02、TC-06 | PARTIAL | READAPT、MODIFY | 是 |
| REQ-005.1 凭证缺失条件、能力提示与不泄密 | AUTOMATABLE_IN_REPO | TC-07、TC-09 | NOT_COVERED | ADD | 是 |
| REQ-005.2 鉴权/权限失败可识别、不为空结果且不降级 | AUTOMATABLE_IN_REPO | TC-05、TC-08、TC-09 | STALE | READAPT、ADD | 是 |
| REQ-005.3 资源失败可识别且 API key-only 不自动创建 | AUTOMATABLE_IN_REPO | TC-08 | STALE | READAPT | 是 |
| REQ-005.4 网络/超时/服务端/解析失败传播 | AUTOMATABLE_IN_REPO | TC-08 | PARTIAL | READAPT | 是 |
| REQ-005.5 日志、异常、测试输出不泄露 Secret | AUTOMATABLE_IN_REPO | TC-07、TC-08、TC-09 | NOT_COVERED | ADD、READAPT | 是 |
| REQ-006.1 Knowledgebase 环境变量与范围文档 | AUTOMATABLE_IN_REPO | TC-11 | NOT_COVERED | ADD | 是 |
| REQ-006.2 Memory 环境变量与范围文档 | AUTOMATABLE_IN_REPO | TC-11 | NOT_COVERED | ADD | 是 |
| REQ-006.3 优先级、空值、管理预检查与 AK/SK 边界文档 | AUTOMATABLE_IN_REPO | TC-11 | NOT_COVERED | ADD | 是 |
| REQ-006.4 示例仅使用明显占位凭证 | AUTOMATABLE_IN_REPO | TC-09、TC-11 | NOT_COVERED | ADD | 是 |
| 真实 Volcengine 服务对有效、过期、无权限 API Key 及资源状态的实际响应 | MANUAL_ONLY | N/A | UNKNOWN | MANUAL_ONLY | 否 |
| 外部日志 / Trace 采集平台最终落库内容不含 Secret | MANUAL_ONLY | N/A | UNKNOWN | MANUAL_ONLY | 否 |

## 自动化缺口与执行限制
| Case ID / 需求点 | 分类 | 无法自动化或当前不可执行的原因 | 证据 | 未覆盖行为 | 建议人工检查点 | 覆盖影响 | 恢复条件 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TC-01～TC-11 当前执行限制 | AUTOMATABLE_IN_REPO | 当前分支只有已批准 Spec，尚无 API Key 业务实现、公共配置入口或对应测试代码；本设计节点禁止编写测试或执行业务验证 | 当前 `VikingKnowledgebaseConfig`、`VikingKnowledgebaseBackend`、`VikingMemoryService` 与两个 wrapper 仅有 AK/SK 路径；目标测试文件尚不存在 | 本轮尚未产生实际自动化结论 | 无需人工代测；待后端实现和 E2E 开发节点按本设计落地后，由正式测试节点执行给定 Maven 入口 | 当前为设计态，不影响设计完备性；实现前不能声称功能通过 | 后端协议与公共配置实现合入当前分支，E2E 开发节点完成 Case 与 Runner 可发现性验证 |
| LIVE-01 真实云端鉴权与资源联通 | MANUAL_ONLY | 仓库与当前 assignment 未提供可自动轮换的真实 API Key、权限矩阵、预置 collection、项目 / resource ID 或清理权限；使用个人凭证或把真实 Secret 注入普通测试报告不安全，现有 Runner 也没有受控云端 lane | `pom.xml` / `core/pom.xml` 仅配置 Surefire 单元 Runner；assignment 只确认仓库级输入，无云端测试账号和资源 artifact | 实际 TLS/域名联通、服务端对有效/过期/无权限 Key 的真实状态码与错误体、目标 collection 的真实可访问性 | 在受控 Volcengine 测试租户中分别用 Knowledgebase / Memory Key 搜索、添加、检索；检查无 AK/SK 时成功、错误 Key 不降级、无权限与资源不存在可区分；禁止在截图或记录中显示凭证 | 中；仓内 fake 可验证客户端路由、请求契约和错误映射，但不能证明外部账号授权与线上服务响应未变化 | 平台提供专用 lane、短期最小权限凭证、可重复的预置 collection、资源定位信息、Secret-safe 注入与报告脱敏后，转为独立受控集成套件 |
| OBS-01 外部观测落库脱敏 | MANUAL_ONLY | 仓库测试可捕获本进程日志和异常，但无法操作或稳定查询外部日志 / Trace 采集平台，也没有当前账号可用的观测查询凭证 | `core` 仅依赖 `slf4j-api`，当前 assignment 未提供外部采集端、topic、trace 查询入口；通用规范原文精确时效性仍为 `needs_live_verification` | SDK 日志离开进程后在采集、传输、索引与展示链路中的最终内容 | 在受控失败调用后按 request / trace 标识查询外部观测数据，只确认无 API Key、AK/SK、Token、Cookie 或完整 Authorization，不复制敏感原文 | 低至中；TC-09 已覆盖 SDK 可控制的日志、异常与测试输出，外部采集链不在本仓能力边界 | 提供可查询的受控观测 lane、非敏感关联 ID 与只读权限后，建立平台侧安全检查；不在本测试仓引入新平台客户端 |
