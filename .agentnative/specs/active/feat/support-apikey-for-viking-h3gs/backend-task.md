---
spec_id: "veadk-java-viking-api-key"
title: "veadk-java Viking 数据面支持 API Key 请求"
status: "draft"
template_id: "backend-task"
schema_version: 1
created_at: "2026-09-29"
updated_at: "2026-09-29"
---

# Tasks: veadk-java Viking 数据面支持 API Key 请求

所有任务以同目录 `backend-design.md` 为实现依据，只修改任务明确列出的 Viking 相关代码、测试和 README。无数据库、Proto/OpenAPI、TOP、Feature Gate、依赖升级或非 Viking 重构任务。

## 1. 配置解析与兼容入口

- [ ] T-1.1 在 `EnvUtil.java` 增加两类 API Key 的可空读取/解析和完整 AK/SK 对检测；实现 trim、空值、大小写 `none/null` 规则，保留现有 `getAccessKey/getSecretKey` 行为，并在 `EnvUtilTest` 覆盖环境基础矩阵。（Design §3、§5、§9；REQ-003、REQ-004）
- [ ] T-1.2 在 `KnowledgeBase.Builder` 增加 `apiKey(String)`，扩展 `VikingKnowledgebaseConfig` 保存已解析 API Key 与管理凭证能力；保留旧 config 构造器、builder 和 `KnowledgeBase.viking(String)`，验证非 Viking/custom backend 不接收 Viking 凭证。（Design §4.1、§5；REQ-001、REQ-003、REQ-004）
- [ ] T-1.3 为 `VikingMemoryService` 增加 `(String appName, String apiKey)` 重载，保留单参数构造器并统一进入实例级凭证解析；无 API Key/AK-SK 时输出 Memory 专属、无凭证值的配置错误。（Design §4.1、§5；REQ-002～REQ-004）

## 2. Knowledgebase API Key 数据面

- [ ] T-2.1 新增 `VikingApiKeyHttpClient` 及可注入 transport seam：只允许既定 Viking origin 和数据面 path，发送 JSON Bearer POST，设置 30 秒 timeout，不重试；严格区分 HTTP、业务 code、JSON/结构失败并生成不含 header/body/Secret 的错误。（Design §1 ADR-001/002、§4.2、§8；REQ-001、REQ-002、REQ-005）
- [ ] T-2.2 扩展 `VikingKnowledgebaseWrapper` 与 `VikingKnowledgebaseBackend`：API Key 查询走 `/api/knowledge/collection/search_knowledge` 并复用现有结果映射；API-key-only 跳过 collection 预检；双凭证管理和 `addDoc` 只走 AK/SK；API Key 失败不降级且正常空数组仍为空集合。（Design §4.2、§5 REQ-001、§6；REQ-001、REQ-004、REQ-005）
- [ ] T-2.3 补齐 `KnowledgeBaseTest`、`VikingKnowledgebaseBackendTest`、`VikingKnowledgebaseWrapperTest`：覆盖显式入口、环境回退、API-key-only、双凭证、AK/SK-only、无凭证、addDoc 管理边界、请求结构、成功/空结果和失败传播。（Design §12.1/12.2；REQ-001、REQ-003～REQ-005）

## 3. Memory API Key 数据面

- [ ] T-3.1 扩展 `VikingMemoryWrapper`，让 `addSession` 和 `searchMemory` 在已解析 API Key 存在时分别调用 `/api/memory/session/add`、`/api/memory/search`；保持消息、metadata、filter、memory type、topK 与公共结果映射，严格区分空数组和依赖失败。（Design §4.2、§5 REQ-002；REQ-002、REQ-005）
- [ ] T-3.2 完成 `VikingMemoryService` 初始化分流：API-key-only 不检查/创建 collection，双凭证仍可用 AK/SK 预检，数据面固定 API Key；无有效 user 消息保持零请求和正常完成。（Design §5 REQ-002、§6；REQ-002～REQ-004）
- [ ] T-3.3 补齐 `VikingMemoryServiceTest`、`VikingMemoryWrapperTest`：覆盖添加、检索、空结果、无有效消息、API-key-only/双凭证/AK-SK-only/无凭证、请求字段和失败传播。（Design §12.1/12.2；REQ-002～REQ-005）

## 4. 安全、错误与兼容验证

- [ ] T-4.1 在 `VikingApiKeyHttpClientTest` 使用假凭证和捕获 transport 断言精确 method/path/header/body/timeout；覆盖 HTTP 401/403/404/500、业务非零 code、网络/超时/中断和畸形 JSON/结构。（Design §4.2、§8、§12.2；REQ-001、REQ-002、REQ-005）
- [ ] T-4.2 使用唯一假 Secret marker 验证异常文本和捕获日志不含 API Key、AK/SK 或完整 Authorization；验证 API Key 失败后 AK/SK transport 零调用，Memory 添加不重试。（Design §4.2、§11、§12.2；REQ-003.5、REQ-005.5）
- [ ] T-4.3 回归旧公共入口、旧 config/wrapper 构造器、AK/SK-only 查询与管理行为，以及非 Viking backend；不得借本需求改变 AK/SK 旧路径的历史错误映射。（Design §4.1、§4.2、§11；REQ-004）

## 5. 文档与验证收口

- [ ] T-5.1 更新 `README.md`、`README_zh.md`，同步说明 `DATABASE_VIKING_API_KEY`、`DATABASE_VIKINGMEM_API_KEY`、显式优先/无效显式值回退、已有 collection 前提、API-key-only 跳过预检和管理操作仍需 AK/SK；示例只用明显占位值。（Design §9、§10、§12；REQ-006）
- [ ] T-5.2 执行 `./mvnw spotless:check` 和设计 §12.3 的定向测试，保存命令、退出码与日志；失败必须修复后重跑。（Design §12.3；REQ-001～REQ-006）
- [ ] T-5.3 执行 `./mvnw -pl core test` 与 `./mvnw -pl core -DskipTests package`，确认无生成漂移；基于 `git diff --unified=0 <before>...HEAD` 和 `core/target/site/jacoco/jacoco.xml` 计算本次可执行变更行/分支的增量覆盖分子、分母和未覆盖清单，覆盖率达到 90% 以上后方可交付。（Design §12.3；REQ-001～REQ-006）
- [ ] T-5.4 提交前逐项归属 `git status --porcelain`，只显式 stage 本任务文件；检查 staged diff 无真实/疑似真实凭证、无依赖或无关修改，提交并 push 当前工作分支，校验远端 ref 等于 after commit。（Design §2、§10；REQ-004～REQ-006）
