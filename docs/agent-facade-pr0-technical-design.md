# VEADK Java Agent Facade PR-0 技术方案

> 目标：先冻结 `Agent` facade 的公共契约，让后续 `ArkLlm`、Runner、knowledgebase/memory、metadata、example 可以并行开发。
> 本阶段只做公共 API 和最小可验证行为，不追求一次性补齐 Python Agent 的全部能力。

## 1. 背景

`veadk-python` 里，用户通常通过 `veadk.Agent(...)` 一站式配置模型、工具、知识库、记忆、运行时和 tracing。Java 当前更多是直接使用 ADK Java 的 `LlmAgent.builder()`，用户需要自己理解 ADK 的 builder、Runner、memory service、tool 注入方式。

PR-0 要解决的是入口不统一的问题：Java 用户应该先有一个 VeADK 自己的 `Agent.builder()`。后续功能可以逐步补齐，但公共契约要先稳定。

## 2. 设计目标

- [ ] 提供 `com.volcengine.veadk.Agent` 作为 Java 侧 VeADK Agent 入口。
- [ ] 提供 `Agent.builder()`，承载 VeADK 默认值和组件装配。
- [ ] 保持 ADK Java 原生执行链路，不重新实现 Runner 或 LLM flow。
- [ ] 为 Runner 和 metadata 暴露稳定的只读访问方法。
- [ ] 为并行开发提供稳定契约，但不为了并行增加主链路抽象。
- [ ] 不使用反射读取 ADK 内部状态。
- [ ] 暂不支持的 Python 动态能力明确 fail-fast。
- [ ] 每个关键设计点都说明：是否是 Java 中合理/惯用的实现，是否与 ADK Java 一致，是否与 `veadk-python` 一致；如果不一致，需要解释原因。

## 3. 非目标

- [ ] 不实现完整 `Runner.run(...)`，只保证后续 Runner 子任务有可读取契约。
- [ ] 不实现完整 metadata extractor，只保证 Agent 能暴露 metadata snapshot。
- [ ] 不实现 YAML builder。
- [ ] 不实现动态 tool discovery、classpath scan 或运行时 import。
- [ ] 不实现 A2UI、A2A、skills、Codex runtime、PiAgent runtime。
- [ ] 不引入新的 Agent runtime、dispatcher、adapter graph。

## 4. 用户视角

PR-0 后，用户预期可以看到这样的 API 形态：

```java
Agent agent =
        Agent.builder()
                .name("rag_agent")
                .description("A helpful assistant.")
                .instruction("Answer user questions with available tools.")
                .modelName("doubao-seed-2-1-pro-260628")
                .tools(new WebSearchTool())
                .knowledgebase(knowledgebaseService)
                .longTermMemory(memoryService)
                .autoSaveSession(true)
                .build();
```

PR-0 不一定让全部自动装配行为都完整可用，但 builder 方法、只读契约和测试应先稳定。

## 5. 为什么 Java 要这样设计

Python 适合用动态对象、字典配置和运行期组装能力。Java 更适合用显式类型和 builder 方法表达能力。这样代码会比 Python 多一些样板，但有几个好处：

- IDE 能自动补全可用能力。
- 编译期能发现参数类型错误。
- Runner、metadata、example 可以依赖 public method，不需要猜内部字段。
- 后续升级 ADK Java 时，风险集中在少数 builder 转发方法里。

因此 Java 版本不是逐字段复制 Python Agent，而是对齐用户心智：用户仍然通过一个 `Agent` 入口配置模型、工具、知识库和记忆，但实现方式采用 Java-native 的 builder 和 typed contract。

## 6. 设计一致性说明标准

后续正式技术方案和 PR 描述中，每个关键设计点都需要明确写出下面三类判断：

| 说明项 | 必须回答的问题 | 示例 |
|---|---|---|
| Java 合理性 | 这是不是 Java 中常见、可维护、类型安全的写法？ | 使用 builder 是 Java SDK 中常见写法，适合表达多可选参数。 |
| ADK Java 一致性 | 是否复用或遵循 ADK Java 现有 API 和生命周期？ | `Agent extends LlmAgent` 与 ADK agent 执行链路一致。 |
| veadk-python 一致性 | 是行为一致、入口一致，还是实现机制一致？ | 对齐 Python 的一站式 `Agent` 入口，但不复制 Python 动态字段实现。 |
| 差异原因 | 如果和 ADK 或 Python 不同，为什么必须不同？ | Java 静态类型和 ADK private 字段限制，不适合运行时反射拼 metadata。 |
| 使用影响 | 这个差异会不会改变用户写法或能力边界？ | 用户需要显式传 `BaseTool`，P0 不支持动态 import tool。 |

推荐在每个子方案里增加这样的说明块：

```md
**一致性与差异**
- Java 合理性：使用 builder 和 typed accessor，是 Java SDK 常见模式。
- ADK Java 一致性：复用 `LlmAgent` 执行链路，不重写 flow。
- veadk-python 一致性：对齐一站式 Agent 入口和默认装配心智。
- 差异原因：Python 可以动态挂字段，Java 需要编译期类型和 public method。
- 使用影响：Java 用户多写显式 builder 方法，但 IDE 可补全，错误更早暴露。
```

如果某个设计与 ADK Java 或 `veadk-python` 的实现方式相同，也要明确写出来。这样评审者能快速判断：这是沿用既有设计，还是有意做 Java-native 取舍。

## 7. Agent 形态

推荐实现：

```java
public final class Agent extends LlmAgent {
    private final AgentContract contract;

    protected Agent(Builder builder) {
        super(builder);
        this.contract = builder.contract();
    }

    public Optional<BaseMemoryService> longTermMemoryService() { ... }

    public Optional<BaseKnowledgebaseService> knowledgebaseService() { ... }

    public boolean autoSaveSession() { ... }

    public String veadkModelName() { ... }

    public AgentMetadataSnapshot metadataSnapshot() { ... }

    public static Builder builder() { ... }
}
```

### 为什么继承 `LlmAgent`

ADK Java 的核心执行逻辑已经在 `LlmAgent` 里，包括 model flow、tools、subAgents、callbacks、schema、outputKey 等。继承 `LlmAgent` 可以直接复用这些能力，让 `Agent` 仍然是一个标准 ADK agent。

不建议在 P0 做包装类，例如：

```java
public final class Agent {
    private final LlmAgent delegate;
}
```

包装会让 Runner、subAgents、ADK callbacks 需要额外适配，容易把 Java 主链路从 ADK 原生链路改成 VeADK 自己的链路。这不符合“不为了 facade 增加主链路复杂度”的原则。

**一致性与差异**

- Java 合理性：继承一个已有的 SDK 基类，并只增加 VeADK 自己的只读契约，是 Java 中比较合理的 facade 写法。
- ADK Java 一致性：与 ADK Java 的 agent model 一致，仍然是 `BaseAgent`/`LlmAgent`，可以直接给 ADK Runner、subAgents 和 callbacks 使用。
- veadk-python 一致性：对齐 Python `veadk.Agent` 作为用户入口的心智。
- 差异原因：Python Agent 可以在动态对象上组织更多运行时字段；Java 需要通过构造期字段和 public accessor 显式表达。
- 使用影响：用户获得 `Agent.builder()` 入口，同时仍可把 `Agent` 当作 ADK `LlmAgent` 使用。

## 8. Builder 设计

推荐实现：

```java
public static final class Builder extends LlmAgent.Builder {
    public Builder modelName(String modelName) { ... }

    public Builder model(BaseLlm model) { ... }

    public Builder modelApiKey(String apiKey) { ... }

    public Builder knowledgebase(BaseKnowledgebaseService service) { ... }

    public Builder longTermMemory(BaseMemoryService service) { ... }

    public Builder autoSaveSession(boolean enabled) { ... }

    @Override
    public Agent build() { ... }
}
```

### 为什么要覆盖常用 builder 方法

ADK Java 的 `LlmAgent.Builder` 已经有 `name(...)`、`description(...)`、`tools(...)` 等方法，但这些方法返回的是 ADK builder 类型。Java 的链式调用依赖返回类型，如果不覆盖，调用会变成：

```java
Agent.builder()
        .name("agent")
        .modelName("model"); // 这里可能接不上
```

因为 `.name("agent")` 返回的类型可能退回 ADK 的 `LlmAgent.Builder`，而不是 `Agent.Builder`。所以 PR-0 需要覆盖常用方法，让它们继续返回 `Agent.Builder`。

这不是为了制造代码量，而是 Java 类型系统下保持用户体验的必要实现。

**一致性与差异**

- Java 合理性：builder 是 Java SDK 中处理多可选参数的常见方式，比超长构造函数更可读。
- ADK Java 一致性：沿用 ADK Java 的 `LlmAgent.Builder` 思路，只是在 VeADK 层补充 `modelName`、`knowledgebase`、`longTermMemory` 等领域方法。
- veadk-python 一致性：对齐 Python `Agent(...)` 的集中配置体验。
- 差异原因：Python 用构造函数关键字参数即可表达大量可选项；Java 更适合 builder，否则构造函数会非常难维护。
- 使用影响：Java 用户写法更链式、更显式，IDE 可以补全字段和类型。

## 9. 公共契约

PR-0 建议冻结以下只读方法：

```java
public Optional<BaseMemoryService> longTermMemoryService();

public Optional<BaseKnowledgebaseService> knowledgebaseService();

public boolean autoSaveSession();

public String veadkModelName();

public AgentMetadataSnapshot metadataSnapshot();
```

### 为什么需要这些方法

Runner 需要知道 Agent 是否配置了长期记忆，metadata 需要知道 Agent 挂了哪些 VeADK 组件。如果没有这些 public method，后续实现可能会去读 ADK private 字段，或者用反射从 builder/agent 内部猜状态。

显式只读方法让依赖关系变清楚：Runner 和 metadata 只依赖 VeADK 的公共契约，不依赖 ADK 内部实现。

**一致性与差异**

- Java 合理性：用 public accessor 暴露跨模块依赖，是 Java 中比反射更稳定的方式。
- ADK Java 一致性：不读取 ADK private 字段，只依赖 ADK public API 和 VeADK 自己的字段。
- veadk-python 一致性：对齐 Python Agent 可被搜索、展示和组件识别的能力。
- 差异原因：Python 可以从对象字典或动态属性中拼装信息；Java 需要在构建期明确保存 VeADK 关心的信息。
- 使用影响：Runner 和 metadata 的实现更稳定，但 P0 只能识别通过 VeADK builder 配置的 VeADK 组件。

## 10. Metadata Snapshot

推荐新增轻量结构：

```java
public final class AgentMetadataSnapshot {
    private final String name;
    private final String description;
    private final String instructionSummary;
    private final String modelName;
    private final List<String> explicitToolNames;
    private final List<String> autoToolNames;
    private final boolean hasKnowledgebase;
    private final boolean hasLongTermMemory;
    private final boolean autoSaveSession;
}
```

### 为什么不是直接从 Agent 反射读取

ADK Java 的很多字段是 private，反射读取会带来三个问题：

- ADK 升级后字段名可能变化。
- Java 模块和安全策略可能限制访问。
- 反射失败通常是运行时错误，用户更难定位。

Agent 在 build 时保存一份 VeADK 关心的 metadata snapshot，更符合 Java 的显式建模方式。

**一致性与差异**

- Java 合理性：metadata snapshot 是不可变、显式、可测试的数据结构，适合 Java 服务端 SDK。
- ADK Java 一致性：不会绕过 ADK 封装读取 private 状态。
- veadk-python 一致性：对齐 Python 侧 agent metadata/search 的结果形态，不对齐其动态采集实现。
- 差异原因：Java 反射读取内部状态有升级风险，也不利于模块边界。
- 使用影响：metadata 更稳定，但需要 builder 在装配组件时同步记录 snapshot。

## 11. P0 暂不支持字段

以下 Python Agent 能力 PR-0 只保留设计位置，不实现：

- `runtime=codex/piagent`
- `enableResponses`
- legacy `skills` / `skillsMode`
- `enableA2ui`
- `enableTunnel`
- YAML 动态加载 tools

建议处理方式：

```java
public Builder enableA2ui(boolean enabled) {
    if (enabled) {
        throw new UnsupportedOperationException(
                "enableA2ui is not supported in veadk-java Agent P0. It is planned for P2.");
    }
    return this;
}
```

### 为什么要 fail-fast

如果 Java silently no-op，用户会以为能力已经生效，排查成本很高。fail-fast 可以明确告诉用户：这个 Python 能力在 Java P0 暂不支持，以及应该等哪个阶段或用什么替代方式。

**一致性与差异**

- Java 合理性：显式抛出 `UnsupportedOperationException` 是 Java SDK 中常见的能力边界表达。
- ADK Java 一致性：不伪造 ADK 尚未装配的运行时能力。
- veadk-python 一致性：承认 Python 有这些字段，但 Java P0 只对齐核心 Agent 使用体验。
- 差异原因：部分能力依赖 Python 生态中的动态 runtime、server、toolset 或前端协议，Java 需要单独设计类型化 API。
- 使用影响：用户会在构建阶段尽早发现能力不可用，而不是运行后才发现没有效果。

## 12. 与后续子任务的 handoff

### A. ArkLlm + Config

依赖 PR-0：

- `modelName(String)`
- `modelApiKey(String)`
- `veadkModelName()`

交付给 Agent facade：

- 默认模型名。
- 显式 apiKey 优先级。
- env fallback 行为。
- 错误信息约定。

### B. Agent Facade 主干

依赖 PR-0：

- `Agent.Builder` 方法名和返回类型。
- `AgentMetadataSnapshot` 字段。

交付给 C/D/E：

- tools/subAgents/callback/schema 转发完成。
- 自定义 `BaseLlm` 优先级。
- `Agent` 只读契约稳定。

### C. Knowledgebase/Memory 自动装配

依赖 PR-0：

- `knowledgebaseService()`
- `longTermMemoryService()`
- `autoSaveSession()`

交付给 Runner：

- Runner 构造时可以从 `Agent` 获取 memory service。
- `autoSaveSession` 的 callback 注入规则。

### D. Runner.run

依赖 PR-0：

- Runner 可以判断 `agent instanceof Agent`。
- 如果是 VeADK Agent，可以读取 memory service。

交付给 docs/example：

- `run(String)` 的默认 user/session 规则。
- final response 提取规则。

### E. Metadata/docs/example

依赖 PR-0：

- `metadataSnapshot()`
- ADK public getter：`name()`、`description()`、`subAgents()`、`tools()` 等。

限制：

- 不读 private 字段。
- 不使用反射。

## 13. 测试建议

PR-0 至少补以下测试：

- [ ] `Agent.builder().build()` 使用默认 name、description、instruction、modelName。
- [ ] `Agent.builder().name(...).modelName(...).build()` 链式调用不断链。
- [ ] `model(BaseLlm)` 优先于 `modelName(...)`。
- [ ] `knowledgebase(...)` 后 `knowledgebaseService()` 可读。
- [ ] `longTermMemory(...)` 后 `longTermMemoryService()` 可读。
- [ ] `autoSaveSession(true)` 后 `autoSaveSession()` 为 true。
- [ ] `metadataSnapshot()` 包含 VeADK 关心字段。
- [ ] 暂不支持能力传 true 时 fail-fast。

## 14. 验收标准

- [x] 公共 API 能编译。
- [x] 最小 `Agent.builder().build()` 测试通过。
- [x] 常用 builder 链式调用不会退回 ADK builder 类型。
- [x] Runner/metadata 后续需要的信息都能通过 public method 获取。
- [x] 没有新增 runner dispatcher、runtime adapter 或为并行协作服务的主链路抽象。
- [x] 没有使用反射读取 ADK 内部字段。
- [x] 技术方案说明每个关键设计点的 Java 合理性、ADK Java 一致性、veadk-python 一致性和差异原因。

当前验证命令：

```bash
./mvnw test
```

验证结果：115 个测试通过，parent/core/example reactor build 成功。

## 15. 待确认问题

- [x] `Agent` 默认模型名先使用 `doubao-seed-2-1-pro-260628`。PR-0 只记录并传给 ADK `model(String)`，暂不自动创建 `ArkLlm`。
- [x] 默认 `description` 和 `instruction` 先采用 Java 独立英文默认值，后续如果需要完全同步 Python 文案，可作为兼容性调整。
- [x] `Agent` 声明为 `final`，避免外部继承造成契约扩散。
- [ ] `modelProvider/modelApiBase/modelExtraConfig` 在 PR-0 是 fail-fast、只存 metadata，还是等 A 任务一起实现。
- [x] `AgentMetadataSnapshot` 放在顶层 `com.volcengine.veadk` 包，和 `Agent` 一起作为 facade 公共 API 暴露。
