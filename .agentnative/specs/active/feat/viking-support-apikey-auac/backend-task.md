---
spec_id: "veadk-java-viking-apikey"
title: "veadk-java Viking API key data-plane auth backend tasks"
status: "draft"
schema_version: 1
created_at: "2026-10-13"
updated_at: "2026-10-13"
---

# Tasks: veadk-java Viking 支持 API key 请求

## 1. T1 配置与 public SDK 契约

- [ ] 1.1 在 `EnvUtil` 新增 `DATABASE_VIKING_API_KEY`、`DATABASE_VIKINGMEM_API_KEY` 常量和读取方法，清洗规则为 trim 后空值、`None`、`null` 视为未配置。
- [ ] 1.2 为 API key 清洗补 `EnvUtilTest`，覆盖有效值、空白值、`None`、`null`、大小写和缺省返回。
- [ ] 1.3 扩展 `VikingKnowledgebaseConfig`，新增 `apiKey` 字段、getter 和 builder；保留现有构造器并保证源码/二进制兼容。
- [ ] 1.4 调整 `VikingKnowledgebaseConfig.fromEnv()`，支持 API-key-only，不因 AK/SK 缺失在构造配置阶段提前失败。
- [ ] 1.5 在 `KnowledgeBase.Builder` 增加 `vikingApiKey(String)` 或等价入口，仅传递给 Viking backend，并补 `KnowledgeBaseTest` 验证显式配置优先于环境变量。
- [ ] 1.6 在 `VikingMemoryService` 新增用户可调用的 public 显式 API key 构造入口 `VikingMemoryService(String appName, String apiKey)`；旧一参构造器委托同一解析逻辑并按显式 `null` 触发环境变量回退，内部/package-private 注入只能作为测试或实现细节。

## 2. T2 KnowledgeBase API key 数据面

- [ ] 2.1 扩展 `VikingKnowledgebaseWrapper` 构造器，支持 `accessKey`、`secretKey`、`apiKey` 三类凭据；旧二参构造器委托新构造器。
- [ ] 2.2 在 `VikingKnowledgebaseWrapper` 新增 package-private 可注入 HTTP transport/client，使用 JDK `HttpClient` 实现 Bearer POST，避免新增依赖。
- [ ] 2.3 修改 `searchKnowledge`：有效 API key 存在时走 Bearer 请求；未配置 API key 时保持现有 `json("SearchKnowledge", null, bodyStr)` AK/SK 路径。
- [ ] 2.4 保持 `isCollectionExists`、`createCollection`、`addDoc` 走现有 AK/SK 管理面路径，不使用 API key。
- [ ] 2.5 在 `VikingKnowledgebaseBackend` 中传递 API key；当有效 API key 存在且 AK/SK 不完整时跳过 `ensureCollection()`。
- [ ] 2.6 补 `VikingKnowledgebaseWrapperTest`，断言 API key search 请求包含 `Authorization: Bearer <key>`，且 AK/SK 分支不受影响。
- [ ] 2.7 补 `VikingKnowledgebaseBackendTest`，断言 API-key-only 构造不调用 `isCollectionExists` / `createCollection`，search result shape 保持。

## 3. T3 Viking Memory API key 数据面

- [ ] 3.1 扩展 `VikingMemoryWrapper` 构造器，支持 Memory API key；旧二参构造器继续可用。
- [ ] 3.2 在 `VikingMemoryWrapper` 新增可注入 HTTP transport/client，复用 KnowledgeBase helper 风格或抽出仅限 integration 包内的最小工具。
- [ ] 3.3 修改 `searchMemory`：有效 API key 存在时向 `/api/memory/search` 发送 Bearer POST；未配置时保持 AK/SK `json(...)`。
- [ ] 3.4 修改 `addSession`：有效 API key 存在时向 `/api/memory/session/add` 发送 Bearer POST；无 API key 时保持 AK/SK `json(...)`。
- [ ] 3.5 在 `VikingMemoryService` 实现 public 显式 API key 入口：`new VikingMemoryService(appName, explicitApiKey)` 中清洗后的有效显式值必须优先于 `DATABASE_VIKINGMEM_API_KEY`，显式空白、`None`、`null` 或 Java `null` 才回退环境变量。
- [ ] 3.6 当有效 API key 存在且 AK/SK 不完整时，`VikingMemoryService` 构造阶段跳过 collection precheck/create；AK/SK 可用时保留现有管理面行为。
- [ ] 3.7 补 `VikingMemoryWrapperTest`，覆盖 search/add 的 Bearer 请求、失败空结果/false、AK/SK 兼容分支和非敏感失败。
- [ ] 3.8 补 `VikingMemoryServiceTest`，覆盖 public 显式 API key 构造入口优先于环境变量、空显式值回退 `DATABASE_VIKINGMEM_API_KEY`、API-key-only 初始化跳过管理面、空 user message 不调用 addSession、search response shape 保持。

## 4. T4 文档与边界说明

- [ ] 4.1 更新 `README.md`，在 Viking Memory / Viking Knowledgebase credential 段落增加 `DATABASE_VIKING_API_KEY` 与 `DATABASE_VIKINGMEM_API_KEY`。
- [ ] 4.2 README 明确：API key 只覆盖 KnowledgeBase search、Memory search、Memory add；collection 管理和 KnowledgeBase `addDoc` / 文档导入仍需 AK/SK 或 IAM。
- [ ] 4.3 README 示例只使用占位符，禁止写入真实 API key、AK/SK 或 Authorization。

## 5. T5 验证与覆盖率

- [ ] 5.1 运行格式或编译检查：`./mvnw -pl core -DskipTests compile`。
- [ ] 5.2 运行定向单测：`./mvnw -pl core -Dtest=EnvUtilTest,VikingKnowledgebaseBackendTest,VikingKnowledgebaseWrapperTest,VikingMemoryServiceTest,VikingMemoryWrapperTest,KnowledgeBaseTest test`。
- [ ] 5.3 使用仓库可用的 JaCoCo 或 Maven coverage 入口统计本次新增/修改代码增量覆盖率，目标不低于 90%；记录分子、分母、未覆盖行或函数。
- [ ] 5.4 检查测试日志和异常断言，确认不包含 API key、AK/SK、session token 或完整 Authorization。
- [ ] 5.5 若具备真实 Viking 凭据和预置 collection，可选执行一组集成验证：KnowledgeBase search、Memory search、Memory add；无凭据时记录未执行原因。

## 6. T6 提交范围

- [ ] 6.1 提交前运行 `git status --porcelain`，逐项确认 dirty 文件均可追溯到本任务。
- [ ] 6.2 只用显式路径 stage 已审查文件，禁止 `git add -A`、`git add .`。
- [ ] 6.3 提交并 push 当前分支，记录 before/after commit、remote、branch、远端 ref 校验和 `git diff --stat <before>..<after>`。
