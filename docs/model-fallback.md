# Model fallback / 模型降级

## 接入

`Agent` 可以按顺序配置多个模型端点。主模型失败后，SDK 在尚未收到任何响应时尝试下一个端点；工具执行和会话管理继续由 ADK Runner 完成。

```java
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.model.ArkLlmConfig;

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

## English

Configure `.modelFallbacks(...)` with independently configured `BaseLlm` instances. Each generation starts at the primary and attempts fallback endpoints in order only if an exception occurs before any response. Once any response is emitted, errors propagate without replay. Requests preserve contents, tools and generation settings while selecting the actual endpoint's model name. Cancellation, normal empty completion and response-level error fields do not trigger fallback. Live connections are unsupported.

Use `new FallbackLlm(primary, fallbacks)` directly or configure the Agent builder. The normal ADK Runner tool loop and response handling remain unchanged. Agent closure releases model resources implementing `AutoCloseable`.

Regression tests cover endpoint selection, failures before and after output, cancellation, resource closure, request preservation, repeated Agent builds, and a real ADK Runner tool round trip using stub model responses. They do not certify remote model quality or availability.
