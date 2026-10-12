# Ark Responses 与结构化输出

通过 `ArkLlmConfig.Api.RESPONSES` 启用 Responses API，可在普通和流式调用中使用工具、JSON Schema 输出、响应 ID 续接及 Token 用量。现有配置继续使用 Chat Completions。

```java
var model = new ArkLlm(ArkLlmConfig.builder()
        .modelName("your-model-id")
        .api(ArkLlmConfig.Api.RESPONSES)
        .thinking("disabled")
        .build());
var session = new ArkResponsesSession();
var agent = Agent.builder()
        .name("assistant")
        .model(model)
        .beforeModelCallback(session.beforeModel())
        .afterModelCallback(session.afterModel())
        .build();
```

凭证沿用 `MODEL_AGENT_API_KEY`，可选 `MODEL_AGENT_API_BASE`。结束使用时关闭 `ArkLlm`，释放连接和线程。

## 会话续接

注册上面的两个回调后，响应 ID 会按会话和 Agent 名称写入 ADK Session State，并通过事件的 state delta 保存。下一次调用在历史前缀、模型、系统指令、工具声明及生成配置均匹配时，携带 `previous_response_id` 和新增内容；历史被压缩、改写或模型配置发生变化时自动发送完整历史。保留完整会话历史，才能在远端响应过期时恢复请求。

Responses 默认设置 `store=true`，由 Ark 保存响应以支持续接。设置 `.responsesStore(false)` 可关闭保存和续接，每次发送完整历史。这里只启用响应保存，不自动开启 Ark 缓存计费选项。

直接调用模型时，可用 `ArkResponsesSession.responseId(response)` 读取 ID；将模型 Content 和新输入加入完整历史后，使用 `ArkResponsesSession.continueFrom(nextRequest, response)` 附上续接信息。模型实例不保存全局会话 ID，因此可以在不同会话间共享。

Java ADK 1.9.0 没有 Python 版的 interaction ID 字段。这里通过响应的 custom metadata 与 Session State 接入，不要求升级 ADK。框架生成的 Event 不复制响应 custom metadata；持久化续接以 state delta 为准。使用多个模型回调时，按 ADK 的回调列表方式组合，保留上述两个会话回调。

Ark 返回 `InvalidParameter.PreviousResponseNotFound` 且尚未产生输出时，适配器仅重试一次，恢复完整历史、系统指令和工具声明。其他错误直接上抛；流式文本或工具输出开始后不再执行这次回退，避免重复输出。

## 结构化输出

```java
var generation = GenerateContentConfig.builder()
        .responseMimeType("application/json")
        .responseJsonSchema(Map.of(
                "type", "object",
                "properties", Map.of("value", Map.of("type", "integer")),
                "required", List.of("value"),
                "additionalProperties", false))
        .maxOutputTokens(256)
        .build();
```

把配置传给 `Agent.builder().generateContentConfig(generation)` 或 `LlmRequest.config`。支持 GenAI `responseSchema` 和原生 `responseJsonSchema`，两者任选其一。适配器转换 GenAI 类型名称和 nullable 字段，保留原生 Schema 的属性定义与字面量。模型需要支持对应的 JSON Schema；实际约束由服务端执行。

| 配置 | Chat Completions | Responses |
|---|---|---|
| 带 Schema | `response_format.json_schema` | `text.format` 的 `json_schema` |
| 仅 `application/json` | `json_object` | `json_object` |
| `maxOutputTokens` | `max_tokens` | `max_output_tokens` |

流式 `partial=true` 仅用于展示增量，完整 JSON 应读取最终聚合响应。Responses 返回 incomplete、failed、错误事件或流被截断时，适配器传播异常，不生成成功的最终响应。输入、输出、总 Token 和上游提供的缓存命中用量只在最终响应中回填。

## 运行示例

配置 `VEADK_TEST_MODEL` 为有权限的模型 ID，并配置上述凭证，使用 JDK 17：

```bash
./mvnw -B -ntp install -DskipTests
./mvnw -B -ntp -pl example exec:java \
  -Dexec.mainClass=com.volcengine.veadk.example.basic.ArkResponsesExample
./mvnw -B -ntp -pl example exec:java \
  -Dexec.mainClass=com.volcengine.veadk.example.basic.ArkResponsesExample \
  -Dexec.args=stream
```

示例连续生成两个 JSON 对象，验证 Schema 输出值、响应 ID 和用量。会产生真实模型调用。当前 Responses 适配支持文本与函数工具；图片和文件输入尚未接入，传入时明确报错。

## English

Select `ArkLlmConfig.Api.RESPONSES` explicitly; Chat Completions remains the default. Register both `ArkResponsesSession` callbacks to persist continuation in session state, scoped by agent name. The adapter reuses a previous response only when the complete history prefix and configuration match. An expired response triggers one full-history retry before any output; other errors and interrupted/incomplete streams propagate.

Responses uses `store=true` by default. Set `responsesStore(false)` to disable remote response storage and continuation. For direct calls, retain full history and use `continueFrom`; read the returned ID with `responseId`. Configure either `responseSchema` or `responseJsonSchema` for strict JSON Schema output in either protocol. The example above validates two live structured turns. Current input support covers text and function tools.
