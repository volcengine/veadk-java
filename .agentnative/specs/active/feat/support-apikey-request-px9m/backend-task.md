# Viking 数据面 API Key 后端任务拆分

## 1. 执行约束

- 依据：同目录 `backend-design.md` 与已批准 `prd-spec.md`。
- 只修改 `volcengine/veadk-java`，保持最小变更；不修改服务端、数据库或非 Viking 能力。
- 每项开发必须同时完成对应测试；新增/修改代码的 JaCoCo 增量覆盖率必须 `> 90%`。
- API Key、Authorization、AK/SK、session token 不得出现在日志、异常、测试快照、提交信息或文档真实值中。
- 若 Viking 实际协议要求新增依赖或改变 public 契约，暂停开发并回到设计节点，不自行扩大范围。

## 2. 任务列表

### T1：实现 Viking 配置解析基础能力

- 来源：设计 §3.1、§3.4。
- 修改 `EnvUtil`，增加 Viking/Memory、cloud provider、BytePlus 凭据和 session token 的非抛错读取。
- 实现统一 normalize/resolve：空白、`None`、`null` 为未配置，显式值优先环境变量。
- 集中实现 provider/region/base URL 解析和 URL 校验；保留现有 AK/SK getter 兼容行为。
- 补 `EnvUtilTest` 参数化测试。
- 完成条件：所有优先级、空值、provider、默认值分支均有确定断言，不输出敏感值。

### T2：扩展 KnowledgeBase 显式配置入口

- 来源：设计 §3.2、§5。
- 将 `VikingKnowledgebaseConfig` 扩展为 immutable builder，保留原构造器。
- 在 `KnowledgeBase.Builder` 增加 `vikingConfig(...)` 并仅向 Viking backend 透传。
- 补 config 与 builder 测试，确认现有 `KnowledgeBase.viking(String)` 和非 Viking backend 不受影响。
- 完成条件：显式配置可表达 Spec §3.3 全部字段，且显式/环境/default 的解析结果可测。

### T3：实现 KnowledgeBase 数据面 API Key 路径

- 来源：设计 §4.1、§4.2、§4.4。
- 增加可注入 transport 的 API Key search client，复用现有请求/响应语义。
- Backend 按配置选择 data client；API Key-only 跳过 collection 预检，API Key + 完整管理凭据时仍以 AK/SK 执行管理预检，管理调用始终走 AK/SK client。
- 修正数据面失败语义：鉴权、非成功、网络和解析失败抛安全异常；成功空结果仍为空。
- 测试 Bearer header、path/body、topK/filter/rerank/chunk diffusion、API Key-only 零 AK/SK/管理交互、双凭据分流、无 API Key 兼容和失败不 fallback。
- 完成条件：REQ-001、REQ-004 的 KnowledgeBase 部分和 AC-001/002/006-011 可由单测追溯。

### T4：增加 Memory 配置对象与显式入口

- 来源：设计 §3.3、§5。
- 新增 `VikingMemoryConfig` immutable builder。
- 保留旧 `VikingMemoryService(String)`，新增 config 重载构造器。
- 解析 project、region、memoryTypes、baseUrl、provider 和两类凭据；补完整单测。
- 完成条件：显式 API Key 及所有配置项可配置，旧构造器源码兼容。

### T5：实现 Memory 数据面 API Key 路径

- 来源：设计 §4.1、§4.3、§4.4。
- 增加可注入 transport 的 Memory API Key client，实现 session add 与 search。
- Service 按配置选择 data client；API Key-only 不读 AK/SK、不检查/创建 collection；API Key + 完整管理凭据时按现有行为执行管理预检。
- 保留消息筛选、metadata、topK、memory types 和返回映射；非成功/网络/解析失败抛安全异常。
- 测试 API Key-only、环境回退、双凭据分流、AK/SK fallback、无消息短路、成功空结果、失败不 fallback。
- 完成条件：REQ-002、REQ-004 的 Memory 部分和 AC-003/004/006-011 可由单测追溯。

### T6：统一数据面异常与凭据安全验证

- 来源：设计 §4.4、§6。
- 新增 `VikingDataPlaneException`，限定非敏感上下文。
- 移除/收敛 Viking 数据面失败路径中完整 request/response/exception 的日志；避免 service 与 integration 重复记录。
- 使用唯一假 Secret 覆盖鉴权失败、服务失败、网络失败、解析失败，断言异常和捕获日志中不存在 Secret/Authorization。
- 完成条件：失败与空结果严格可区分，保留 code/request ID 时不泄露凭据。

### T7：更新中英文用户文档

- 来源：设计 §7.1，Spec REQ-006。
- 更新 `README.md`、`README_zh.md`：显式配置示例、全部相关环境变量、优先级、默认值、Volcengine/BytePlus 差异、仅 API Key 已有 collection 限制、管理面 AK/SK 规则。
- 示例只用明显占位符；说明 API Key 失败不自动 fallback。
- 完成条件：AC-014 全部信息可在两份 README 中找到，且无形似真实凭据。

### T8：执行验证并形成开发交接证据

- 来源：设计 §8、§10。
- 运行 `./mvnw spotless:check`。
- 运行 `./mvnw -pl core -am -DskipTests compile`。
- 运行 Viking 定向测试与 `./mvnw -pl core test`。
- 生成 `core/target/site/jacoco/jacoco.xml`，按本次 diff 的有效可测行计算增量覆盖率 `> 90%`，记录分子/分母、未覆盖行。
- 将真实 E2E 所需配置、用例和安全注意事项交接下游；凭据只由环境注入。
- 完成条件：每条命令、退出码和关键摘要有证据；失败不得写成“预计通过”。

## 3. 建议执行顺序

`T1 → (T2 → T3) 与 (T4 → T5) → T6 → T7 → T8`。T3/T5 可在 T1 完成后独立实施，但同一工作区仍遵循单 writer。

## 4. 验收追踪

| Task | Requirement | 验收与测试重点 |
| --- | --- | --- |
| T1 | REQ-003 | AC-005、AC-013；配置清洗/优先级/provider |
| T2、T3 | REQ-001、REQ-004 | AC-001、002、006-011；KB 鉴权选择与边界 |
| T4、T5 | REQ-002、REQ-004 | AC-003、004、006-011；Memory add/search 与边界 |
| T6 | REQ-005、NFR-SEC-001、NFR-REL-001 | AC-010-012；异常、空结果、脱敏 |
| T7 | REQ-006 | AC-014；中英文配置与兼容说明 |
| T8 | NFR-TEST-001 | AC-015；格式化、构建、单测、覆盖率与 E2E 交接 |

## 5. 不实施项

- 不新增用户画像 API、自动重试、Feature Gate、数据库 migration 或服务端改造。
- 不改变 collection 管理与知识库文档导入的 API Key 支持范围。
- 不顺带重构非 Viking 配置、日志或 HTTP client。
