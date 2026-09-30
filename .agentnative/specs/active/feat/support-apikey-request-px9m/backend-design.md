# Viking 数据面 API Key 后端技术设计

## 1. 文档信息与范围

- Current Repo：`volcengine/veadk-java`。
- 分支：`feat/support-apikey-request-px9m`。
- 上游 Spec：`artifacts/workflow-node-artifact-yew7rjobnkks15jk1313/prd-spec.md`，已通过 Spec Review 和 Human 批准。
- 原始澄清：`artifacts/workflow-node-artifact-yew7psqlmorv0yt4fsh5/veadk-java-viking-apikey-requirement.md`。
- 设计域：backend（Java SDK）。
- 目标：为 Viking KnowledgeBase 搜索、Viking Memory 记忆添加和查询增加 API Key 数据面鉴权，同时保持既有 AK/SK 管理面及未配置 API Key 时的数据面行为。
- 非目标：不修改 Viking 服务端协议、数据库、非 Viking 能力，不让 API Key 承担 collection 或文档管理鉴权，不新增 Java 当前不存在的用户画像公开 API。

仓库未提供 `.agentnative/templates/default/design.md` 和 `task.md`；本文按 `tech-design` 要求保留需求对齐、现状、方案、兼容/安全、验证、风险和追踪等可执行信息。

## 2. 仓库现状与约束

### 2.1 当前调用链

KnowledgeBase：

`KnowledgeBase.Builder` → `VikingKnowledgebaseBackend` → `VikingKnowledgebaseWrapper`。Backend 构造时通过 `VikingKnowledgebaseConfig.fromEnv()` 强制读取 AK/SK，并立即执行 `isCollectionExists`，不存在时调用 `createCollection`。搜索由 `SearchKnowledge` 完成，失败目前被转换为空列表。

Memory：

`VikingMemoryService` → `VikingMemoryWrapper`。Service 构造时强制读取 AK/SK，并立即检查/创建 collection；`addSessionToMemory` 和 `searchMemory` 分别调用 `AddSession`、`SearchMemory`。Wrapper 的非成功响应目前返回 `false` 或空列表。

### 2.2 现有可复用能力

- `VikingKnowledgebaseConfig` 已是 KnowledgeBase 配置承载点，但目前只有 AK/SK、rerank 和 chunk diffusion。
- `KnowledgeBase.Builder` 已提供稳定的显式配置入口模式，可新增 Viking 专用配置而不改变已有方法。
- `Mem0RuntimeClient` 已使用 Java 17 `java.net.http.HttpClient`，证明仓库无需新增依赖即可实现 Bearer 数据面请求。
- wrapper 已定义现有 Viking 路径、请求体和返回解析，API Key 客户端应复用相同数据结构与解析语义。
- 测试使用 JUnit 5、Mockito static/construction mock；Maven Surefire 与 JaCoCo 已配置。

### 2.3 约束结论

- 仅增加 Java 配置对象、数据面传输选择及对应测试/README；不引入新三方依赖。
- API Key 值只能保存在内存配置/请求 header 中，禁止进入 `toString`、日志、异常或测试输出。
- 现有 public 数据面方法的参数和返回类型不变。
- 数据面调用不可因 API Key 失败自动回退 AK/SK；是否回退只由“是否解析到有效 API Key”决定。
- 管理面继续使用现有 Volcengine SDK 签名客户端；仅 API Key 模式不构造管理客户端、不做管理预检。

## 3. 配置设计

### 3.1 统一清洗与优先级

在 `EnvUtil` 增加非抛错的 Viking 配置读取方法，并提供包内可复用的值清洗规则：`trim` 后为空、大小写不敏感等于 `none` 或 `null` 均返回 `null`。所有配置按以下函数解析：

```text
resolve(explicit, env, defaultValue):
  if normalize(explicit) != null: return normalize(explicit)
  if normalize(env) != null: return normalize(env)
  return defaultValue
```

AK/SK 的既有 `getAccessKey()` / `getSecretKey()` 抛错语义不变；新配置解析只在确定需要 AK/SK 管理客户端或 AK/SK 数据面 fallback 时调用它们。BytePlus 凭据选择按 cloud provider 解析对应 `BYTEPLUS_*` 变量，Volcengine 继续解析 `VOLCENGINE_*`；session token 为可选值。

### 3.2 KnowledgeBase 配置

扩展 `VikingKnowledgebaseConfig` 为 immutable builder 配置，并保留现有 public 构造器以保证二进制/源码兼容。新增字段：

| 字段 | 环境变量 | 默认值/规则 |
| --- | --- | --- |
| `apiKey` | `DATABASE_VIKING_API_KEY` | 无 |
| `project` | `DATABASE_VIKING_PROJECT` | `default` |
| `region` | `DATABASE_VIKING_REGION`，再回退 `REGION` | `cn-beijing` |
| `resourceId` | `DATABASE_VIKING_RESOURCE_ID` | 无 |
| `version` | `DATABASE_VIKING_VERSION` | `2` |
| `baseUrl` | `DATABASE_VIKING_BASE_URL` | Volcengine：`https://api-knowledgebase.mlp.{region}.volces.com`；BytePlus：`https://api-knowledgebase.mlp.{region}.bytepluses.com` |
| `cloudProvider` | `AGENTKIT_CLOUD_PROVIDER`，再回退 `CLOUD_PROVIDER` | `volcengine` |
| `accessKey` / `secretKey` / `sessionToken` | provider 对应变量 | 无；需要 AK/SK 路径时校验；API Key-only 不触发 IAM 探测 |
| `rerank` / `chunkDiffusionCount` | 保持现有行为 | `true` / `3` |

`KnowledgeBase.Builder` 新增 `vikingConfig(VikingKnowledgebaseConfig)`；仅在 backend 为 `viking` 时传给 `VikingKnowledgebaseBackend`。不传时使用 `VikingKnowledgebaseConfig.builder().build()` 按环境变量解析。`KnowledgeBase.viking(String)` 行为保持不变。

### 3.3 Memory 配置

新增 immutable `VikingMemoryConfig` 及 builder，字段为 `apiKey`、`project`、`region`、`memoryTypes`、`baseUrl`、`cloudProvider`、`accessKey`、`secretKey`、`sessionToken`，环境变量和默认值严格对应 Spec §3.4。`memoryTypes` 将规范化后的逗号分隔值转换为非空列表；默认 `sys_event_v1,sys_profile_v1`。Volcengine endpoint 为 `https://api-knowledgebase.mlp.{region}.volces.com`；BytePlus 与冻结 Python revision `31d2c67b` 一致，固定使用受支持的 `cn-hongkong` 和 `https://api-knowledgebase.mlp.cn-hongkong.bytepluses.com`，除非显式 `baseUrl` 覆盖。

保留 `VikingMemoryService(String appName)`，新增 `VikingMemoryService(String appName, VikingMemoryConfig config)` 作为显式配置入口；旧构造器委托环境配置。

### 3.4 Endpoint 规则

显式 `baseUrl` 优先。未显式配置时按 §3.2/§3.3 的固定规则生成 host；集中函数用参数化测试固化 Volcengine/BytePlus 结果，禁止在多个 wrapper 中重复拼接。BytePlus KnowledgeBase 若显式 region 为空或为中国大陆 region，则按 Python 行为归一为 `cn-hongkong`。URL 仅接受 `http`/`https` 且去除末尾 `/`；非法 URL 在构造阶段抛出不含凭据的 `IllegalArgumentException`。

## 4. 鉴权与客户端设计

### 4.1 客户端职责拆分

每个 Viking 组件内部保存两个可选职责客户端：

- data client：有 API Key 时使用 Bearer API Key HTTP 请求；否则使用现有 AK/SK 签名 wrapper。
- management client：仅在解析到完整管理凭据时构造，始终使用 AK/SK/IAM 规则；API Key 不传入该客户端。

不新增跨 Viking 的通用框架。KnowledgeBase 与 Memory 请求/响应不同，分别在现有 integration package 内增加小型 API Key data client/transport，以降低重构范围；共享的配置清洗只放在 `EnvUtil`。HTTP 使用 Java 17 `HttpClient`，设置现有 5 秒连接/请求超时，不自动重试，避免记忆写入重复。

### 4.2 KnowledgeBase 流程

构造流程：

1. 校验 collection name。
2. 解析 `VikingKnowledgebaseConfig`。
3. 若 `apiKey != null`，构造 API Key data client；不得读取 AK/SK。仅当配置中同时存在完整管理凭据时额外构造 management wrapper。
4. 若 `apiKey == null`，按既有方式要求管理凭据，并让现有 wrapper 同时承担数据面与管理面。
5. API Key-only 模式跳过 `isCollectionExists` / `createCollection`；API Key 与完整管理凭据同时存在时，按冻结 Python 行为保留管理预检/自动创建。无 API Key 时保持原 AK/SK 预检/创建。无论是否执行预检，后续数据面始终按 API Key 优先。

搜索流程：

```text
blank query -> 保持返回空列表
apiKey configured -> POST {baseUrl}/api/knowledge/collection/search_knowledge
                  -> Authorization: Bearer <apiKey>
                  -> body 使用 name、project、query、limit、dense_weight=0.5、post_processing
                  -> 有 filter 时使用 query_param.doc_filter；有 resourceId 时增加 resource_id
apiKey absent     -> 现有 SearchKnowledge 签名请求
success empty     -> 空列表
non-success/network/parse failure -> 抛出 VikingDataPlaneException
```

`addDoc` 及其它文档管理路径只调用 management client；仅 API Key 时抛出明确的 `IllegalStateException`（说明缺少管理凭据，不包含任何凭据值）。API Key search body 明确按上文承载 `project` 和可选 `resource_id`；`version` 仅用于 collection 管理，不进入 API Key search。请求字段与返回解析逐项复用/对齐当前 wrapper 和冻结 Python revision `31d2c67b`，不扩展 public 业务参数。

### 4.3 Memory 流程

构造流程与 KnowledgeBase 一致：API Key 模式不读取 AK/SK、不调用 `isCollectionExists`/`createCollection`；无 API Key 时保留现有检查和自动创建。

数据面流程：

- `addSessionToMemory` 保留现有 user 文本事件筛选；无有效消息仍直接完成且不发请求。存在消息时调用 API Key data client 的 `/api/memory/session/add` 或现有 AK/SK wrapper。
- `searchMemory` 保留 collection、userId、query、topK 和 memory types，调用 `/api/memory/search` 或现有 wrapper。
- Python Memory 通过 `VikingMem` 的 `APIKey` auth 和 `get_collection(collection_name, project_name)` 访问。Java 当前依赖中没有该 Memory SDK，因此采用等效 HTTP data client：`Authorization: Bearer <apiKey>`，请求体保留 `collection_name` 并新增 `project` 定位信息；真实 E2E 负责验证服务端兼容性。若开发前依赖树确认现有 `volc-sdk-java` 已暴露等效 APIKey auth，则优先直接复用，且不得新增依赖。
- `addSession` 非成功必须抛异常，不能继续以 `false` 表示成功完成；`searchMemory` 非成功必须抛异常，只有成功响应中的空 `result_list` 返回空列表。
- 当前 Java 无用户画像公开方法，本轮不新增；未来同一 backend 增加时复用同一 data client。

### 4.4 异常契约

新增 `VikingDataPlaneException`（runtime exception），只携带 operation、非敏感服务端 code、request ID、HTTP/status 与 cause。消息不得包含 API Key、Authorization、AK/SK、session token、完整请求 header 或完整响应体。

对现有 AK/SK 数据面 wrapper 同步修正失败分支：非成功响应和解析异常抛 `VikingDataPlaneException`，不再返回正常空结果；成功且结果数组缺失/为空仍返回空列表。管理方法的 boolean 兼容语义保持不变。Memory service 不重复记录并包装同一异常；最近的 integration 层完成一次固定消息日志或直接向上透传。

## 5. 接口、数据与兼容性

- Public API 新增项仅为配置 builder、`KnowledgeBase.Builder.vikingConfig(...)` 和 `VikingMemoryService` 重载构造器；现有方法不删除、不改签名。
- 返回模型 `KnowledgebaseEntry`、`MemoryEntry`、`SearchMemoryResponse` 不变；无数据库、Schema、DDL/DML 或数据迁移。
- API Key 和 AK/SK 同时存在时：数据面固定 API Key，管理预检及管理调用仅 AK/SK；API Key 失败不 fallback。
- 未配置 API Key 时：构造、collection 预检/创建、数据面与管理面保持现有 AK/SK 行为。
- 回滚为旧 SDK 后新增 API Key 配置会被忽略，用户必须恢复 AK/SK；无持久化数据回滚。
- BytePlus/Volcengine 差异集中在 provider 配置、凭据变量和 endpoint 推导，不改变鉴权优先级。

## 6. 安全、可靠性与可观测性

- 敏感字段：API Key、Authorization、AK/SK、session token，全部禁止日志/异常/Trace/测试快照。README 只使用 `<your-...>` 占位符。
- 日志仅允许固定 operation、project、collection、region、非敏感 code/request ID；不打印请求 header 和完整 response。现有输出完整异常/response 的数据面日志应在改造路径中收敛。
- 每次业务调用只发一个数据面请求；不新增自动重试。特别是 `addSessionToMemory` 不重试，以免非幂等写重复。
- 超时沿用现有 wrapper 的 5 秒连接/读取配置；JDK HTTP data client 同步配置等价 connect/request timeout。
- 不新增 Metrics/Trace 字段：这是客户端鉴权切换，仓库没有对应稳定埋点约定；异常保留 request ID 供调用方关联。
- 不需要 Feature Gate：API Key 为可选配置，未配置即自然走旧路径；快速回滚方式是移除 API Key 或回退 SDK 版本。

适用公共规范：`ArkClaw/Agentkit 管控日志打印规范`（revision 20）与 `ArkClaw/Agentkit观测数据脱敏手册`（revision 204）仅用于“凭据不得进入日志/异常、错误单点记录、保留 request ID”原则。其 Go/Friday 组件、Access Log 拦截器和内部发布流程不适用于本开源 Java SDK。精确线上规范状态需回源核验，但不影响本设计的更严格凭据保护要求。

## 7. 变更文件计划

### 7.1 生产代码

- `[MODIFY] core/src/main/java/com/volcengine/veadk/utils/EnvUtil.java`：配置清洗及 Viking/BytePlus 环境变量读取。
- `[MODIFY] core/src/main/java/com/volcengine/veadk/knowledgebase/KnowledgeBase.java`：显式 Viking config builder 入口。
- `[MODIFY] core/src/main/java/com/volcengine/veadk/knowledgebase/backends/viking/VikingKnowledgebaseConfig.java`：完整配置与解析。
- `[MODIFY] core/src/main/java/com/volcengine/veadk/knowledgebase/backends/viking/VikingKnowledgebaseBackend.java`：数据/管理面选择及初始化边界。
- `[MODIFY] core/src/main/java/com/volcengine/veadk/integration/vikingknowledgebase/VikingKnowledgebaseWrapper.java`：AK/SK 数据面失败语义；管理行为保持。
- `[NEW] core/src/main/java/com/volcengine/veadk/integration/vikingknowledgebase/VikingKnowledgebaseApiKeyClient.java`：Bearer 搜索。
- `[NEW] core/src/main/java/com/volcengine/veadk/memory/viking/VikingMemoryConfig.java`：Memory 配置与解析。
- `[MODIFY] core/src/main/java/com/volcengine/veadk/memory/viking/VikingMemoryService.java`：显式配置、初始化边界、data client 选择。
- `[MODIFY] core/src/main/java/com/volcengine/veadk/integration/vikingmemory/VikingMemoryWrapper.java`：AK/SK 数据面失败语义。
- `[NEW] core/src/main/java/com/volcengine/veadk/integration/vikingmemory/VikingMemoryApiKeyClient.java`：API Key add/search。
- `[NEW] core/src/main/java/com/volcengine/veadk/integration/viking/VikingDataPlaneException.java`：安全的统一数据面异常。
- `[MODIFY] README.md`、`README_zh.md`：配置入口、变量、优先级、数据/管理边界、兼容与占位示例。

如开发时确认现有 wrapper 能以最小方式安全复用相同传输，可不新增两个 client 文件，但必须保持本文的职责、鉴权和测试边界；不得为复用扩大到非 Viking 模块。

### 7.2 测试代码

- `[MODIFY] EnvUtilTest`：清洗、provider、显式/环境/默认优先级辅助逻辑。
- `[NEW/MODIFY] VikingKnowledgebaseConfigTest`、`VikingMemoryConfigTest`：全字段解析与默认值。
- `[MODIFY] KnowledgeBaseTest`、`VikingKnowledgebaseBackendTest`：显式 config 透传、API Key 跳过预检、AK/SK 兼容、管理缺凭据。
- `[MODIFY] VikingMemoryServiceTest`：API Key-only 初始化、add/search 选择、消息过滤、AK/SK 兼容。
- `[MODIFY] 两个 wrapper test`：非成功/解析失败抛异常，成功空结果仍为空。
- `[NEW] 两个 API Key client test`：使用注入式 fake transport 捕获 method/path/body/header；断言 Bearer、project/collection 和响应解析，同时确保失败异常/日志不含唯一假 Secret。

## 8. 验证计划

### 8.1 静态与构建

```text
./mvnw spotless:check
./mvnw -pl core -am -DskipTests compile
```

### 8.2 定向单测与回归

```text
./mvnw -pl core -Dtest=EnvUtilTest,VikingKnowledgebaseConfigTest,VikingMemoryConfigTest,VikingKnowledgebaseBackendTest,VikingKnowledgebaseWrapperTest,VikingKnowledgebaseApiKeyClientTest,VikingMemoryServiceTest,VikingMemoryWrapperTest,VikingMemoryApiKeyClientTest test
./mvnw -pl core test
```

单测覆盖：

- 显式值 > 环境变量 > 默认值；空白/`None`/`null` 回退。
- KnowledgeBase/Memory API Key-only 构造不读取 AK/SK、不检查/创建 collection。
- API Key + AK/SK 时数据面选 API Key，管理面选 AK/SK；API Key 错误不 fallback。
- 无 API Key 完整保持 AK/SK 构造与预检。
- 空 query/空有效消息不发请求；成功空结果与所有失败可区分。
- topK、filter、rerank、chunk diffusion、memory types 和返回映射不变。
- 假 Secret 不出现在异常 message/cause 可见文本及捕获日志。

开发节点使用 JaCoCo XML 对本次新增/修改可测行统计增量覆盖率，目标 `> 90%`；报告 `core/target/site/jacoco/jacoco.xml`，逐文件列出分子/分母与未覆盖行。

### 8.3 真实数据面验收

- 使用预建 KnowledgeBase collection：分别以显式 API Key、环境变量 API Key 搜索，验证结构和空结果。
- 使用预建 Memory collection：添加带唯一内容的会话后查询并命中。
- 使用无权限/无效 API Key 验证异常与空结果可区分，检查输出无 Secret。
- 移除 API Key、配置 AK/SK，回归 KnowledgeBase/Memory 原有数据面和一次管理操作。
- Volcengine 与 BytePlus 分别验证 endpoint/provider 解析；真实凭据只由测试环境注入，不写入命令和报告。

## 9. 风险、灰度与回滚

| 风险 | 控制 |
| --- | --- |
| API Key endpoint/header 与服务端实际契约偏差 | 请求构造集中化，fake transport 单测 + 真实 E2E；不静默 fallback |
| 构造阶段仍误触管理调用 | 对 EnvUtil 和 management wrapper 做零交互断言 |
| 失败继续伪装空结果 | wrapper/client 的非成功、网络、解析异常均做拒绝测试 |
| Secret 经异常或日志泄漏 | 唯一假 Secret 覆盖各失败路径并检索输出 |
| 旧 AK/SK 用户行为回归 | 无 API Key 全链路回归，保留旧 public 构造器与方法 |
| Memory 写入重复 | 客户端不自动重试，调用方显式决定重试 |

灰度以配置为边界：先在测试环境仅对预建 collection 启用 API Key，再扩大使用；观测鉴权失败码、request ID 和业务成功率。回滚优先移除 API Key 恢复 AK/SK，必要时回退 SDK；无数据库或不可逆数据迁移。

## 10. Requirement → Design → Task → Test 追踪

| Requirement / AC | Design | Task | Test |
| --- | --- | --- | --- |
| REQ-001 / AC-001、002、011 | §3.2、§4.2 | T2、T3 | KnowledgeBase config/backend/client 单测；KB E2E |
| REQ-002 / AC-003、004、011 | §3.3、§4.3 | T4、T5 | Memory config/service/client 单测；add→search E2E |
| REQ-003 / AC-005、013 | §3.1-§3.4 | T1、T2、T4 | 清洗、优先级、provider/endpoint 参数化测试 |
| REQ-004 / AC-006-009 | §4.1-§4.3 | T3、T5 | API Key-only 零管理交互、管理缺凭据、双凭据测试 |
| REQ-005 / AC-010-012 | §4.4、§6 | T3、T5、T6 | 失败/空结果、假 Secret、request ID 测试 |
| REQ-006 / AC-014 | §7.1 | T7 | README 静态审查与占位值检索 |
| AC-015 / NFR-TEST-001 | §8 | T6、T8 | Maven/JaCoCo 与真实 E2E 证据 |

## 11. 待确认项

无阻塞性产品待确认项。KnowledgeBase API Key 请求字段和 BytePlus endpoint 已依据冻结 Python revision `31d2c67be6fb9bd7f3139de42d2615c2c4a73e6f` 固化。Memory 使用等效 HTTP 鉴权是 Java 当前无 Viking Memory SDK 依赖下的最小方案，须由真实 E2E 验证；若验证表明服务端不接受该等效方式且必须新增依赖或改变公开契约，应停止开发并回到 BACKEND_DESIGN 处理范围变化。
