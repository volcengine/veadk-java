# Agent Facade Worktree 任务拆分

> 基线分支：`agent-facade`
>
> 原则：每个 worktree 一个独立分支、一个清晰目标。除非任务明确要求，否则不要改别的子任务主文件。公共契约改动必须先同步，因为其他任务会依赖 PR-0 的 `Agent` facade。

## 1. 创建 Worktree

推荐在当前仓库同级目录创建：

```bash
git worktree add ../veadk-java-arkllm -b task/arkllm-config agent-facade
git worktree add ../veadk-java-runner -b task/runner-run agent-facade
git worktree add ../veadk-java-kb-memory -b task/kb-memory-agent-assembly agent-facade
git worktree add ../veadk-java-metadata -b task/metadata-docs agent-facade
```

查看：

```bash
git worktree list
```

完成后删除某个 worktree：

```bash
git worktree remove ../veadk-java-runner
```

## 2. Worktree A：ArkLlm / Config

### 分支

```bash
cd ../veadk-java-arkllm
git branch --show-current
# task/arkllm-config
```

### 目标

补齐 `ArkLlm` 显式配置能力，让 `Agent.builder().modelName(...).modelApiKey(...)` 后续能可靠创建 Ark 模型，同时保持当前 env 读取逻辑兼容。

### 要实现什么

- 增强 `ArkLlm` 构造能力：
  - `ArkLlm(String modelName)` 保持兼容，继续从 `MODEL_AGENT_API_KEY` 读取。
  - 新增显式 API key 构造方式。
  - 预留或支持 base URL / API base。
  - 保持 `thinking` 参数兼容。
- 新增轻量配置对象或 builder：
  - 推荐 `ArkLlmConfig` 或 `ArkLlm.Builder`，避免构造函数越来越长。
  - 配置优先级：显式参数 > env > 默认值。
- 更新 `Agent.Builder.modelApiKey(...)`：
  - 如果只需要 PR0 契约先接上，可让它保存 key 并在 build 时创建 `ArkLlm`。
  - 如果还没准备好 Agent 自动创建模型，需要在文档里说明保持 fail-fast。

### 不要做什么

- 不实现 `Runner.run(...)`。
- 不做 knowledgebase/memory 自动注入。
- 不引入全局配置中心或复杂 registry。
- 不为了支持多 provider 改动 `Runner` 或 tools 主链路。

### Java 实现说明

Java 里更适合用类型化 config/builder 表达 API key、base URL、thinking 这类可选项，而不是像 Python 一样通过 dict 动态传参。这样 IDE 能补全字段，也能在构建模型时集中校验必填项。

### Handoff 给其他任务

交付后告诉 `kb-memory-agent-assembly` 和 `runner-run`：

- `Agent.builder().modelName(...)` 是否已经会自动创建 `ArkLlm`。
- `modelApiKey(...)` 是否已可用。
- 未配置 key 时抛出的异常类型和错误信息是什么。

### 验收

- `./mvnw test` 通过。
- 新增测试覆盖：
  - env key 路径仍可用。
  - 显式 key 优先于 env。
  - 缺 key 时报错清晰。
  - `thinking` 兼容。

## 3. Worktree B：Runner.run

### 分支

```bash
cd ../veadk-java-runner
git branch --show-current
# task/runner-run
```

### 目标

给 Java 用户提供简单同步入口：

```java
Runner runner = new Runner(agent);
String answer = runner.run("hello");
```

### 要实现什么

- 在 `com.volcengine.veadk.runner.Runner` 新增便捷方法：
  - `String run(String message)`
  - 可选：`String run(Content content)`
  - 可选：`String run(String userId, String sessionId, String message)`
  - 可选：支持传入 `RunConfig`
- 自动创建 session：
  - 默认 `appName` 使用 agent name 或构造参数。
  - 默认 `userId` 可用稳定默认值，例如 `"default_user"`。
  - 默认 `sessionId` 每次生成，或由用户显式传入。
- 消费 ADK `runAsync(...)` 事件流：
  - 优先取 `event.finalResponse()`。
  - 提取最终文本。
  - 如果没有 final response，取最后一个非空模型文本，并清晰处理空结果。
- 如果传入的是 VeADK `Agent`：
  - Runner 构造时可读取 `agent.longTermMemoryService()`。
  - 不要为了这个功能新增 runner dispatcher。

### 不要做什么

- 不改 `Agent.Builder` 公共 API，除非发现 Runner 必须新增 accessor。
- 不做 KB/Memory 工具自动注入。
- 不改 `ArkLlm` 构造逻辑。
- 不做复杂多轮对话管理框架。

### Java 实现说明

Java 用户不熟悉 ADK 的 session/runAsync 细节时，同步 `run(String)` 很重要。但实现应只是 ADK Runner 的薄封装：创建 session、调用 ADK runAsync、消费事件并返回文本，不重新实现 agent runtime。

### Handoff 给其他任务

交付后告诉 `kb-memory-agent-assembly`：

- Runner 是否会读取 `Agent.longTermMemoryService()`。
- `autoSaveSession` 是否由 Runner 负责触发，还是由 Agent callback 负责。
- 默认 userId/sessionId 策略。

### 验收

- `./mvnw test` 通过。
- 新增测试覆盖：
  - `run(String)` 能自动创建 session。
  - final response 提取正确。
  - 空响应错误信息清晰。
  - 显式 userId/sessionId 可用。

## 4. Worktree C：KB / Memory Agent Assembly

### 分支

```bash
cd ../veadk-java-kb-memory
git branch --show-current
# task/kb-memory-agent-assembly
```

### 目标

让 `Agent.builder()` 配置 knowledgebase 和 long-term memory 后，自动完成工具和 callback 装配，接近 Python `veadk.Agent` 的一站式体验。

### 要实现什么

- `knowledgebase(BaseKnowledgebaseService service)`：
  - build 时自动追加 `LoadKnowledgebaseTool`。
  - metadata 中标记 auto tool。
  - 避免当前 `LoadKnowledgebaseTool` 的 static service 导致多 Agent 串扰。
- `longTermMemory(BaseMemoryService service)`：
  - 自动追加 memory search tool。
  - 如果 ADK Java 已有官方 memory tool，优先复用官方实现。
  - 如果没有，再做 VeADK 自己的薄工具。
- `autoSaveSession(true)`：
  - 自动挂载 `SaveSessionToMemoryCallback`，或和 Runner 的保存策略明确分工。
  - 避免重复保存。

### 不要做什么

- 不使用反射读取 Agent 或 ADK private 字段。
- 不做全局 static service 保存当前 Agent 的 KB/memory。
- 不为了自动装配新增复杂 runner/agent 主链路。
- 不实现 YAML 动态 tool discovery。

### Java 实现说明

Python 可以较自然地在运行时给 Agent 挂组件；Java 更适合在 builder 阶段把工具列表和 callback 列表组装好。这样每个 Agent 都持有自己的 service 实例，不会出现全局 static 状态污染。

### Handoff 给其他任务

交付前需要和 `runner-run` 对齐：

- `autoSaveSession` 到底由 callback 保存，还是 Runner 保存。
- 如果 Runner 也支持手动保存，需要避免同一次 run 保存两次。

交付后告诉 `metadata-docs`：

- 自动注入的 tool 名称是什么。
- metadata 中 `explicitToolNames` 和 `autoToolNames` 如何区分。

### 验收

- `./mvnw test` 通过。
- 新增测试覆盖：
  - 两个 Agent 配不同 knowledgebase 不串扰。
  - knowledgebase 自动 tool 可被 canonicalTools 发现。
  - memory search tool 自动注入。
  - `autoSaveSession(true)` callback 挂载且不重复。

## 5. Worktree D：Metadata / Docs / Examples

### 分支

```bash
cd ../veadk-java-metadata
git branch --show-current
# task/metadata-docs
```

### 目标

补齐面向用户和集成侧的 metadata 输出、README/example，让 PR0 后的 Agent facade 能被看懂、能被示例验证。

### 要实现什么

- 新增或完善 metadata extractor：
  - 读取 `Agent.metadataSnapshot()`。
  - 输出 Agent 基础信息、tools、subAgents、components、searchSources。
  - 对非 VeADK `LlmAgent` 保持兼容降级。
- 更新 README / README_zh：
  - 展示 `Agent.builder()` 最小示例。
  - 展示 `modelName(...)` 和 `model(BaseLlm)` 两种写法。
  - 明确 PR0 暂不支持的能力。
- 更新 example：
  - 可新增一个最小 `AgentFacadeExample`。
  - 不要求真实调用 Ark，避免 example 依赖真实 key 才能编译。

### 不要做什么

- 不反射读取 ADK private 字段。
- 不新增动态扫描工具。
- 不改变 Runner 行为。
- 不把文档写成 Python 实现说明，重点写 Java 用户如何使用。

### Java 实现说明

metadata 在 Java 里应来自显式 public contract，而不是运行时扫描对象内部字段。这样对 ADK 版本升级更稳定，也更容易测试。

### Handoff 给其他任务

需要从 `kb-memory-agent-assembly` 获取：

- 自动工具名称。
- components/searchSources 的最终字段含义。

需要从 `arkllm-config` 获取：

- `modelApiKey` 和 config 的最终用户写法。

需要从 `runner-run` 获取：

- `Runner.run(...)` 的最终签名和 session 策略。

### 验收

- `./mvnw test` 通过。
- README 示例能编译或至少与实际 public API 一致。
- metadata extractor 不使用反射。
- 文档明确 Java 与 Python 的相同点和差异原因。

## 6. 合并顺序建议

推荐顺序：

1. `agent-facade` 已作为 PR0 基线。
2. 先合 `task/arkllm-config`，因为它会影响默认模型装配。
3. 合 `task/runner-run`，提供用户可运行入口。
4. 合 `task/kb-memory-agent-assembly`，因为它依赖 Agent 契约，也需要和 Runner 的保存策略对齐。
5. 最后合 `task/metadata-docs`，统一反映最终 API。

如果 `metadata-docs` 只写 PR0 文档，也可以更早合；但 README/example 最好等 Runner 和 ArkLlm 签名稳定后再最终收口。

## 7. 给每个 Worktree Agent 的通用提示词

```text
你在 veadk-java 的独立 git worktree 中工作。
基线分支是 agent-facade。
只做当前子任务，不顺手改其他子任务范围。
不要使用反射读取 ADK private 字段。
不要为了并行协作新增 runner/agent 主链路抽象。
如果必须修改 Agent facade 公共 API，先说明原因和影响。
完成后运行 ./mvnw test。
提交到当前 task/* 分支，commit message 使用清晰的 feat/test/docs 前缀。
```
