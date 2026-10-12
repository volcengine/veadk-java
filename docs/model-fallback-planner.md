# Model fallback and planning / 模型降级与规划

## 接入

`Agent` 可以按顺序配置多个模型端点，并挂载 `PlanReActPlanner`。主模型失败后，SDK 在尚未收到任何响应时尝试下一个端点；工具执行和会话管理继续由 ADK Runner 完成。

```java
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.model.ArkLlmConfig;
import com.volcengine.veadk.planners.PlanReActPlanner;

var primary = new ArkLlm(ArkLlmConfig.builder()
        .modelName(System.getenv("PRIMARY_MODEL"))
        .apiKey(System.getenv("PRIMARY_API_KEY"))
        .build());
var backup = new ArkLlm(ArkLlmConfig.builder()
        .modelName(System.getenv("BACKUP_MODEL"))
        .apiKey(System.getenv("BACKUP_API_KEY"))
        .apiBase(System.getenv("BACKUP_API_BASE"))
        .build());
var agent = Agent.builder()
        .name("assistant")
        .model(primary)
        .modelFallbacks(backup)
        .planner(new PlanReActPlanner())
        // .tools(...) 挂载任务所需工具 / register task tools
        .build();
try {
    String answer = new Runner(agent).run("请根据可用信息完成任务");
} finally {
    agent.close().blockingAwait();
}
```

后备模型也可以使用 `OpenAiCompatibleLlm` 或其他 ADK `BaseLlm` 实现，各自配置凭证和地址。`modelFallbacks(...)` 同时支持显式 `.model(...)` 和由 `.modelName(...)` 自动创建的主模型。配置中的模型应支持任务需要的工具、输入格式与输出约束。Agent 关闭时一并关闭实现 `AutoCloseable` 的模型；同一 Agent 内重复配置的模型实例只关闭一次。

## 切换规则

- 每次生成、每次订阅均从主模型开始，按配置顺序尝试；各模型每次只尝试一次。
- 同步异常及订阅期间的异常均可触发切换，但必须尚未收到任何 `LlmResponse`。
- 收到文本、工具调用或空响应后发生错误，直接向调用方传播错误，避免重复内容或重复工具执行。
- 所有模型失败时传播最后一个异常。正常结束的空流、响应中的错误字段和取消订阅不会触发切换。
- 只替换请求中的模型名，保留历史、工具和生成参数；不会记录凭证，也不会自动重试工具。
- 仅支持 `generateContent`，不支持实时连接 `connect`。SDK 不为此增加重试次数、超时或退避策略，模型客户端原有重试设置仍然有效。

## Planner 行为与扩展

ADK Java 1.9.0 未提供 Python 的 `BasePlanner` 接口。本 SDK 提供 `com.volcengine.veadk.planners.Planner`，通过 `PlanningLlm` 在模型请求前追加规划指令、在响应后处理规划内容；不依赖 ADK 的 `planning(boolean)` 字段。

`PlanReActPlanner` 采用与 Python Plan-Re-Act 对齐的规划、行动、观察及最终答案标签，支持普通模型，不要求原生 thinking 能力。模型仍以真实 function call 调用工具，调用 ID、参数、Token 统计及提供方元数据保留。它是提示词驱动的行为约定，不保证模型一定遵循计划，也不是任务调度器。

`Runner.run()` 返回最终答案中的普通文本；规划内容以 `thought=true` 保存在事件里。使用 `runAsync()` 的应用应按 `Part.thought` 区分规划与用户答案。流式文字分片全部标为 thought，在完整最终响应中再解析 `/*FINAL_ANSWER*/`，因此用户答案在最终响应时交付。模型适配器应在非 partial 最终响应中提供完整内容，Ark 与 VeADK 的标准流式适配路径遵循此约定。无标签的最终普通文本保持原样。

自定义 Planner 实现 `buildPlanningInstruction(LlmRequest)` 和 `processPlanningResponse(LlmResponse)`，需保证可被并发请求共享，并保留工具调用、usage 和 metadata。可直接使用 `new PlanningLlm(model, planner)` 和 `new FallbackLlm(primary, fallbacks)`，或使用 Agent builder 组合二者；builder 在 fallback 外层应用 planner，避免把规划处理异常当作端点故障再次请求模型。

## English

Configure `.modelFallbacks(...)` with independently configured `BaseLlm` instances. Each generation starts at the primary and attempts fallback endpoints in order only if an exception occurs before any response. Once any response is emitted, errors propagate without replay. Requests preserve contents, tools and generation settings while selecting the actual endpoint's model name. Cancellation, normal empty completion and response-level error fields do not trigger fallback. Live connections are unsupported.

`.planner(new PlanReActPlanner())` enables prompt-based planning through VeADK's `Planner` extension; ADK Java 1.9.0 has no Python-compatible `BasePlanner` API. The normal ADK tool loop remains in use. Planning text is marked as thought in events, and `Runner.run()` returns only non-thought text. Streaming partial text is marked as thought until the complete final response can be split safely across tags. This requires adapters to emit accumulated content in their final non-partial response. Custom planners must be thread-safe and preserve tool calls, usage and provider metadata.

The deterministic regression tests cover endpoint selection, failures before and after output, cancellation, resource closure, request preservation, planning tags, and a real ADK Runner tool round trip using stub model responses. They do not certify remote model quality or availability.
