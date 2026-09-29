# VEADK Java Agent Facade 实施计划

> 目标：让 `veadk-java` 的 Agent 用户入口与 `veadk-python` 的 `veadk.Agent` 核心体验对齐。
> 范围：优先完成 Agent facade、Runner 便捷入口、最小配置和 metadata 可发现性；skills、A2UI、A2A、外部 runtime 等生态能力后置。

## 1. Java-native 对齐原则

本计划的目标是与 `veadk-python` 的核心使用体验对齐，不是逐字段、逐实现机制照搬。Java 版本应优先尊重 Java 类型系统、ADK Java public API、builder 模式和编译期安全。

- [ ] 体验对齐优先：Java 用户能用 `Agent.builder()`、`new Runner(agent).run(...)`、自动知识库/记忆装配完成 Python Agent 的核心场景。
- [ ] 实现方式 Java-native：允许与 Python 内部实现不同，只要行为和用户心智一致。
- [ ] 不为并行协作增加主链路复杂度：不引入仅用于拆任务的 adapter、dispatcher、registry 或中间抽象。
- [ ] 尽量不使用反射：metadata 使用 `Agent` 自身保存的 builder 快照和 ADK public getter；工具/YAML 的动态加载后置，并优先使用显式 registry。
- [ ] 对动态能力做边界说明：Python 中依赖动态类型、运行时导入、装饰器或字典配置的能力，Java 中需要改为显式 builder、类型化配置、接口或 registry。
- [ ] 暂不支持能力 fail-fast：不要 silently no-op，错误信息需要说明 Java 版本暂不支持、建议替代方案和后续阶段。

需要在技术方案中明确说明的语言差异：

| Python 能力/习惯 | Java 设计取舍 | 说明 |
|---|---|---|
| 动态字段和宽松 `dict` 配置 | 类型化 builder + `VeadkConfig` | 降低运行时错误，保留少量 `Map<String, Object>` 作为 P1 扩展口。 |
| 运行时 import/反射式装配 tool | P0 显式传入 `BaseTool`/toolset，P1 可用显式 `ToolRegistry` | 不做 classpath scan，不让工具加载成为隐式副作用。 |
| Python Agent 可混合大量可选能力 | Java P0 保持薄 facade | 先覆盖高频主路径，runtime/A2UI/A2A/skills 后置。 |
| metadata 可从动态对象上拼装 | Agent 构建时记录 typed metadata snapshot | 避免读取 ADK private 字段或依赖反射。 |
| callback/运行态状态较灵活 | 复用 ADK Java callback 和 Runner lifecycle | 不重新实现一套运行时状态机。 |
| 多模型 fallback 可用列表/运行时切换 | P1 引入显式 fallback model abstraction | P0 先支持单模型和自定义 `BaseLlm`。 |

## 2. 技术方案说明要求

后续输出技术方案时，需要默认面向“不熟悉 Java 但熟悉 Python/Agent 概念”的读者解释。方案不能只写“新增某个类/接口”，还要说明为什么这么设计、和 Python 的差异是什么、以及这样做对使用者有什么影响。

每个子方案建议包含以下信息：

- [ ] 用户视角：这个能力完成后，Java 用户怎么用。
- [ ] Java 实现方式：新增哪些类、方法、builder 字段或测试。
- [ ] 设计原因：为什么 Java 里要这么写，而不是照搬 Python 实现。
- [ ] Python 对齐点：对齐的是哪个 Python Agent 能力或使用心智。
- [ ] Java 差异点：哪些地方因为静态类型、泛型、builder、ADK public API 或包可见性限制，需要换一种实现。
- [ ] 不采用方案：明确说明为什么不使用反射、动态 import、全局 registry、额外 runner dispatcher 等做法。
- [ ] 对主链路影响：说明是否影响 `Agent.builder()`、`Runner.run(...)`、tools、memory、knowledgebase 主路径。
- [ ] 测试和验收：说明用哪些单测或 example 证明行为正确。

推荐说明格式：

```md
### 子任务：Agent facade 主干

**要实现什么**
Java 用户通过 `Agent.builder()` 创建 VeADK Agent，不再直接使用 ADK 的 `LlmAgent.builder()`。

**为什么这么写**
Java 没有 Python 那种运行时随意挂字段的模式，而且 ADK Java 已经提供了类型化 `LlmAgent.Builder`。因此 Java 版本应该在 builder 层收敛 VeADK 默认值和组件装配，而不是在运行期动态修改 Agent。

**和 Python 的对齐/差异**
对齐 Python 的 `veadk.Agent(...)` 一站式入口；差异是 Java 使用显式 builder 方法和编译期类型检查。

**主链路影响**
`Agent` 仍走 ADK Java 的 `LlmAgent` 执行链路，不新增额外 runner dispatcher。
```

## 3. 目标用户体验

P0 完成后，Java 用户应能用 VeADK 自己的入口创建和运行 Agent：

```java
Agent agent =
        Agent.builder()
                .name("web_search_agent")
                .description("通用助手，可联网搜索实时信息。")
                .instruction("当用户的问题需要实时信息时，先调用 web_search。")
                .modelName("doubao-seed-2-1-pro-260628")
                .modelApiKey(System.getenv("MODEL_AGENT_API_KEY"))
                .tools(new WebSearchTool())
                .build();

Runner runner = new Runner(agent);
String answer = runner.run("今天有什么新闻？");
```

知识库和长期记忆应能通过 Agent 自动装配：

```java
Agent agent =
        Agent.builder()
                .name("rag_agent")
                .knowledgebase(new VikingKnowledgebaseService("rag_agent"))
                .longTermMemory(new Mem0MemoryService("rag_agent"))
                .autoSaveSession(true)
                .build();
```

## 4. P0 必做清单

### 4.0 PR-0 已落地范围

- [x] 新增 `com.volcengine.veadk.Agent`，作为 VeADK Java 的 Agent facade 入口。
- [x] `Agent` 继承 ADK Java `LlmAgent`，保持原生执行链路。
- [x] 新增 `Agent.builder()`，并覆盖常用 ADK builder 方法，保证链式调用返回 `Agent.Builder`。
- [x] 新增 Java 默认值：`DEFAULT_NAME`、`DEFAULT_DESCRIPTION`、`DEFAULT_INSTRUCTION`、`DEFAULT_MODEL_NAME`。
- [x] 新增 `modelName(String)` 作为 VeADK 语义入口；PR-0 阶段先委托到 ADK `model(String)` 并记录 metadata。
- [x] 新增 `knowledgebase(BaseKnowledgebaseService)`、`longTermMemory(BaseMemoryService)`、`autoSaveSession(boolean)` 公共契约。
- [x] 新增 `AgentMetadataSnapshot`，记录 name、description、instruction、model、显式工具、自动工具占位、knowledgebase/memory 标记。
- [x] 对 `runtime`、`enableResponses`、`skills`、`skillsMode`、`enableA2ui`、`enableTunnel`、`modelApiKey` 做 fail-fast。
- [x] 新增 `AgentTest` 覆盖 facade 类型、公共契约、metadata、`modelName`、链式 builder 和 fail-fast。

PR-0 有意没有实现以下行为，避免提前扩大主链路改动：

- [ ] `modelName` 自动创建 `ArkLlm`，留给 ArkLlm/config 子任务。
- [ ] `knowledgebase(...)` 自动追加 `LoadKnowledgebaseTool`，留给 KB 自动装配子任务。
- [ ] `longTermMemory(...)` 自动追加 memory search tool，留给 Memory 自动装配子任务。
- [ ] `autoSaveSession(true)` 自动挂载 callback，留给 Memory/Runner 联动子任务。
- [ ] `Runner.run(...)` 便捷入口，留给 Runner 子任务。

### 4.1 Agent Facade

- [x] 新增 `com.volcengine.veadk.Agent`。
- [x] 提供 `Agent.builder()` 链式构建入口。
- [x] 对齐 Python 默认值：
  - [x] `name = "veAgent"`。
  - [x] 默认 `description`。
  - [x] 默认 `instruction`。
  - [x] 默认模型名。
- [ ] 支持模型装配：
  - [x] `modelName(String modelName)`。
  - [x] `model(BaseLlm model)`，用户传入模型时优先使用自定义模型。
  - [ ] `modelApiKey(String apiKey)`。
  - [ ] 预留 `modelProvider(String provider)`。
  - [ ] 预留 `modelApiBase(String apiBase)`。
  - [ ] 预留 `modelExtraConfig(Map<String, Object> extraConfig)`。
- [ ] 未显式传 `model` 时，自动创建 `ArkLlm`。
- [ ] 支持 ADK 原生能力透传：
  - [x] `tools(...)`。
  - [x] `subAgents(...)`。
  - [x] before/after agent callbacks。
  - [x] before/after model callbacks。
  - [x] before/after tool callbacks。
  - [x] input schema。
  - [x] output schema。
  - [x] output key。
  - [x] code executor。
  - [x] max steps / max LLM calls 相关配置。
- [ ] 支持 `knowledgebase(BaseKnowledgebaseService service)`，并自动追加 `LoadKnowledgebaseTool`。
- [x] 支持 `knowledgebase(BaseKnowledgebaseService service)` 公共契约。
- [ ] 支持 `longTermMemory(BaseMemoryService memoryService)`，并自动追加 ADK `LoadMemoryTool`。
- [x] 支持 `longTermMemory(BaseMemoryService memoryService)` 公共契约。
- [x] 支持 `autoSaveSession(boolean enabled)`。
- [ ] `autoSaveSession(true)` 时，自动挂载保存 session 到长期记忆的 callback。
- [x] 对暂不支持的 Python Agent 字段 fail-fast 或明确标记实验：
  - [x] `runtime=codex/piagent`。
  - [x] `enableResponses`。
  - [x] legacy `skills` / `skillsMode`。
  - [x] `enableA2ui`。
  - [x] `enableTunnel`。

### 4.2 ArkLlm 构造能力增强

- [ ] 增加显式 API key 构造参数。
- [ ] 保留当前从 `MODEL_AGENT_API_KEY` 读取的兼容路径。
- [ ] 预留 API base/base URL 配置能力。
- [ ] 保持 `thinking` 参数兼容。
- [ ] 补充测试：
  - [ ] env key 路径仍可用。
  - [ ] 显式 key 优先于 env。
  - [ ] 未配置 key 时错误信息清晰。

### 4.3 Runner 便捷入口

- [ ] 在 `Runner` 中新增 `run(String message)`，返回最终文本。
- [ ] 新增 `run(Content content)`。
- [ ] 新增 `run(List<Content> contents)` 或等价多轮入口。
- [ ] 自动生成默认 `userId`。
- [ ] 每次 `run` 默认生成新的 `sessionId`，避免静态默认 session。
- [ ] 自动创建或复用 session。
- [ ] 构建默认 `RunConfig`。
- [ ] 支持调用方传入 `RunConfig`。
- [ ] 消费事件流，提取 final response 或最后一个非空文本。
- [ ] 新增 `saveSessionToLongTermMemory(...)` 手动保存入口。
- [ ] 如果 Agent 挂了长期记忆，Runner 自动使用该 memory service。

### 4.4 最小配置系统

- [ ] 新增 `com.volcengine.veadk.config.VeadkConfig`。
- [ ] 新增 `ConfigLoader`。
- [ ] 支持从环境变量读取配置。
- [ ] 预留 `.env` 加载能力。
- [ ] 预留 `config.yaml` 加载能力。
- [ ] 明确优先级：显式参数 > env > config.yaml > 默认值。
- [ ] P0 覆盖配置项：
  - [ ] `MODEL_AGENT_NAME`。
  - [ ] `MODEL_AGENT_API_KEY`。
  - [ ] `MODEL_AGENT_API_BASE`。
  - [ ] `MODEL_AGENT_PROVIDER`。
  - [ ] `MODEL_AGENT_MAX_LLM_CALLS`。
  - [ ] `VOLCENGINE_ACCESS_KEY`。
  - [ ] `VOLCENGINE_SECRET_KEY`。
  - [ ] `REGION`。
  - [ ] `DATABASE_MEM0_*`。
  - [ ] `AGENTKIT_TOOL_*`。
  - [ ] `OBSERVABILITY_OPENTELEMETRY_*`。

### 4.5 Agent Metadata

- [x] 新增 `AgentMetadataSnapshot` 数据结构。
- [ ] 新增 `AgentMetadataExtractor`。
- [ ] 输出 Agent 基础信息：
  - [ ] id。
  - [x] name。
  - [x] description。
  - [x] instruction 摘要。
  - [x] model。
- [x] 输出显式 tools 列表。
- [ ] 输出 subAgents 树。
- [ ] 输出 components：
  - [ ] knowledgebase。
  - [ ] longTermMemory。
  - [ ] shortTermMemory 预留。
  - [ ] tracer 预留。
  - [ ] toolset 预留。
  - [ ] plugin 预留。
- [ ] 输出 searchSources：
  - [ ] web。
  - [ ] knowledge。
  - [ ] memory。

## 5. P0 测试计划

- [x] `Agent` 默认值测试。
- [x] `Agent.builder()` 创建最小 Agent 测试。
- [ ] 自定义 `BaseLlm` 优先级测试。
- [x] `model(BaseLlm)` 记录自定义模型名测试。
- [ ] `modelName + modelApiKey` 自动创建 `ArkLlm` 测试。
- [x] tools 注入测试。
- [ ] subAgents 注入测试。
- [ ] knowledgebase 自动注入 `LoadKnowledgebaseTool` 测试。
- [ ] longTermMemory 自动注入 `LoadMemoryTool` 测试。
- [ ] `autoSaveSession(true)` callback 注入测试。
- [ ] `Runner.run(String)` 自动 session 测试。
- [ ] `Runner.run(String)` final response 提取测试。
- [ ] `Runner.run(..., RunConfig)` 覆盖默认配置测试。
- [ ] `saveSessionToLongTermMemory(...)` 测试。
- [ ] `ConfigLoader` env 优先级测试。
- [ ] `AgentMetadataExtractor` components/searchSources 测试。

## 6. P0 验收标准

- [ ] 用户不需要直接调用 `LlmAgent.builder()`，即可创建 VeADK Agent。
- [ ] 用户不需要手动创建 session，即可 `new Runner(agent).run("hello")`。
- [ ] 不配置自定义 model 时，默认使用 Ark 模型。
- [ ] 显式传入 `modelApiKey` 时，不依赖环境变量。
- [ ] 挂载 knowledgebase 后，Agent 自动拥有 `load_knowledgebase` 工具。
- [ ] 挂载 longTermMemory 后，Agent 自动拥有 memory search 工具。
- [ ] `autoSaveSession(true)` 能将会话保存到长期记忆。
- [ ] metadata 能识别 tools、subAgents、knowledgebase、memory、searchSources。
- [x] PR-0 相关单测通过。
- [ ] README 或 example 中有 Java Agent facade 示例。

## 7. P1 补齐清单

### 7.1 Memory Facade

- [ ] 新增 `LongTermMemory` facade。
- [ ] 支持 backend 枚举：
  - [ ] `local`。
  - [ ] `viking`。
  - [ ] `mem0`。
- [ ] 提供统一方法：
  - [ ] `addSessionToMemory(Session session)`。
  - [ ] `searchMemory(String appName, String userId, String query)`。
- [ ] 新增 auto-save policy：
  - [ ] default：只保存 user text。
  - [ ] all：保存 user/assistant/system 和更多 part 类型。
  - [ ] custom：自定义 roles/event types。

### 7.2 ShortTermMemory Facade

- [ ] 新增 `ShortTermMemory` facade。
- [ ] P1 支持 local/in-memory session service。
- [ ] 预留 sqlite/mysql/postgresql/JDBC 后端。
- [ ] 支持 `afterCreateSessionCallback`。
- [ ] 支持 `afterLoadMemoryCallback`。

### 7.3 KnowledgeBase Facade

- [ ] 新增 `KnowledgeBase` facade。
- [ ] 支持 backend：
  - [ ] `local`。
  - [ ] `viking`。
- [ ] 提供统一方法：
  - [ ] `addFromText(...)`。
  - [ ] `addFromFiles(...)`。
  - [ ] `addFromDirectory(...)`。
  - [ ] `search(...)`。
- [ ] 保留 `BaseKnowledgebaseService` 兼容路径。

### 7.4 Model Fallback 与 Extra Config

- [ ] 支持 `modelName(List<String>)`，第一个为 primary，其余为 fallback。
- [ ] 支持 `modelFallbacks(...)`。
- [ ] 支持 `modelProvider`。
- [ ] 支持 `modelApiBase`。
- [ ] 支持 `modelExtraConfig`。
- [ ] `enableResponses` 继续后置，除非 Ark Java SDK 已确认支持 Responses API。

### 7.5 Tracing 自动装配

- [ ] 从配置读取 TLS tracing。
- [ ] 自动初始化 OpenTelemetry。
- [ ] 预留 APMPlus exporter。
- [ ] 预留 Cozeloop exporter。
- [ ] Runner 暴露 `getTraceId()`。
- [ ] Runner 暴露 `saveTracingFile(...)` 预留。

### 7.6 YAML AgentBuilder

- [ ] 新增 `AgentBuilder.fromYaml(Path path)`。
- [ ] 支持默认根节点 `root_agent`。
- [ ] 支持 `type`：
  - [ ] `Agent`。
  - [ ] `SequentialAgent`。
  - [ ] `ParallelAgent`。
  - [ ] `LoopAgent`。
- [ ] 支持递归构建 `sub_agents`。
- [ ] 支持通过显式 `ToolRegistry` 加载 tools。
- [ ] 对未知字段给出清晰错误或透传策略。

## 8. P2/P3 后置清单

- [ ] ADK `SkillToolset` 兼容封装。
- [ ] legacy `Agent.skills` 迁移提示。
- [ ] MCP Toolset 封装。
- [ ] A2UI：`enableA2ui`、catalog、`send_a2ui_json_to_client`。
- [ ] A2A server/client/registry。
- [ ] Tunnel toolset。
- [ ] Codex runtime。
- [ ] PiAgent runtime。
- [ ] Ark Responses API。
- [ ] Responses cache。
- [ ] Realtime / voice / multimodal live。
- [ ] dataset generation callback。
- [ ] supervisor flow。

## 9. 推荐实施顺序

1. 增强 `ArkLlm` 构造能力。
2. 新增 `Agent` facade 和默认值常量。
3. 实现 tools/subAgents/model/callback/schema 转发。
4. 实现 knowledgebase 和 longTermMemory 自动工具注入。
5. 实现 `autoSaveSession`。
6. 实现 `Runner.run(...)`。
7. 实现最小 `VeadkConfig` / `ConfigLoader`。
8. 实现 `AgentMetadataExtractor`。
9. 补 P0 单测。
10. 更新 README 和 example。
11. 进入 P1 facade 抽象。

## 10. P0 并行拆分建议

P0 可以并行推进，但建议先用半天冻结公共接口契约，避免多人同时修改 `Agent`、`Runner` 和 metadata 时反复返工。

### 10.1 前置公共契约

- [ ] 冻结 `Agent` builder 的 P0 方法名、默认值和包名。
- [ ] 冻结 Agent 暴露给 Runner/metadata 的只读访问方法：
  - [ ] `getLongTermMemoryService()` 或等价方法。
  - [ ] `getKnowledgebaseService()` 或等价方法。
  - [ ] `isAutoSaveSession()` 或等价方法。
  - [ ] `getModelName()` 或等价方法。
- [ ] 冻结 P0 暂不支持字段的处理策略：fail-fast 或 experimental no-op。
- [ ] 冻结测试用例命名和 mock/stub 方式。
- [ ] 冻结“不为并行增加主链路代码”的规则：并行协作只通过公共契约、handoff 文档和测试对齐。
- [ ] 冻结“不使用反射实现 P0 能力”的规则：确需动态扩展时使用显式 registry 或 public API。

### 10.2 并行子任务

| 子任务 | 主要范围 | 可并行性 | 依赖 | 建议负责人 |
|---|---|---|---|---|
| A. `ArkLlm` + 配置基础 | 显式 API key、env 兼容、base URL 预留、`VeadkConfig`/`ConfigLoader` env 读取 | 高 | 无，优先启动 | 1 人 |
| B. `Agent` facade 主干 | `com.volcengine.veadk.Agent`、builder、默认值、model/tools/subAgents/callback/schema 透传 | 中 | 依赖公共契约；可先用当前 `ArkLlm` 构造占位 | 1 人 |
| C. Knowledgebase/Memory 自动装配 | `knowledgebase(...)` 自动追加 `LoadKnowledgebaseTool`，`longTermMemory(...)` 自动追加 `LoadMemoryTool`，`autoSaveSession` callback | 中 | 依赖 B 的 builder 骨架；需要确认 tool 去重策略 | 1 人 |
| D. `Runner.run(...)` 便捷入口 | `run(String)`、默认 user/session、`RunConfig`、final response 提取、手动保存 session | 中 | 依赖公共契约；不强依赖 B 完成，可用 `BaseAgent` 先实现 | 1 人 |
| E. Metadata + example/docs | `AgentMetadata`、`AgentMetadataExtractor`、components/searchSources、README/example | 高 | metadata 依赖 B/C 的只读访问方法；example 依赖 P0 API 稳定 | 1 人 |

### 10.3 推荐并行排期

1. 第 0.5 天：共同冻结公共契约。
2. 第 1-2 天：A、B、D 并行启动；C 基于 B 的草稿同步开发。
3. 第 3-4 天：B/C/D 联调，E 开始 metadata 和 example。
4. 第 5 天：补齐 P0 单测、修兼容性问题、更新 README。

如果只有 2 个人，建议拆成两条线：

- [ ] 线 1：A + B + C，负责 Agent 创建和自动装配主路径。
- [ ] 线 2：D + E + 测试/docs，负责运行入口、可发现性和验收闭环。

如果有 3-4 个人，建议按 A/B/C/D 拆开，E 在 API 稳定后接入。

### 10.4 合并顺序

1. 先合 A：降低后续 Agent 默认模型构造的阻塞。
2. 再合 B：建立 `Agent` facade 主体。
3. 再合 D：让最小 demo 可以端到端运行。
4. 再合 C：补齐 knowledgebase/memory 自动装配。
5. 最后合 E：metadata、example、README 和验收文档。

### 10.5 主要并行风险

- [ ] `LoadKnowledgebaseTool` 当前持有静态 `knowledgebaseService`，多个 Agent 并存时可能互相覆盖，C 任务需要优先处理。
- [ ] `autoSaveSession` 使用 ADK callback 和 Runner memory service，B/C/D 三线要共享同一套 memory service 约定。
- [ ] `Runner.run(...)` 的 final response 提取依赖 ADK Event 结构，D 任务需要先补单测锁定行为。
- [ ] `Agent` 如果直接继承 `LlmAgent`，builder 可扩展性会受 ADK final/构造约束影响；B 任务需要先验证继承或包装方案。
- [ ] metadata 如果通过 ADK private 字段或反射读取内部状态，升级风险高；E 任务必须使用 `Agent` 自己保存的 P0 元数据快照和 ADK public getter。

## 11. 工作量估算

| 阶段 | 范围 | 预估 |
|---|---|---:|
| P0 | Agent facade + Runner.run + config + metadata + tests/docs | 10-16 人日 |
| P1 | Memory/KnowledgeBase facade + fallback + tracing + YAML builder | 10-15 人日 |
| P2/P3 | skills、MCP、A2UI、A2A、runtime、Responses、realtime 等 | 4-8 周 |
