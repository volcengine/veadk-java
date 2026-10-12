# Ark 推理接入：参数、工具调用与用量

本批为使用 Ark Chat Completions 的 Agent 补齐三项接入能力。SA 可以配置生成参数、接入连续或并行工具调用，并从响应或会话事件读取模型用量。

| 功能 | 接入方式 | 行为与边界 |
|---|---|---|
| 生成参数 | `GenerateContentConfig.temperature`、`topP`、`maxOutputTokens` | 普通和流式调用使用相同配置；未配置时保留服务端默认值。当前协议将 `maxOutputTokens` 映射为 `max_tokens`，具体取值范围由模型校验 |
| 工具调用上下文 | 保留 `FunctionCall.id`，将它写入对应的 `FunctionResponse.id` | 历史中保留工具名称、参数与 JSON 结果；同名工具并行执行时按 ID 关联，结果返回顺序可以不同 |
| Token 用量 | `LlmResponse.usageMetadata()` 或 ADK `Event.usageMetadata()` | 提供输入、输出和总 Token 数。流式输出只在最终聚合响应附带用量，末尾独立 usage 消息也能读取；上游未提供时为空 |

## 配置示例

```java
var generation = GenerateContentConfig.builder()
        .temperature(0.25f)
        .topP(0.75f)
        .maxOutputTokens(256)
        .build();

var agent = Agent.builder()
        .name("assistant")
        .model(arkLlm)
        .generateContentConfig(generation)
        .build();
```

使用 ADK Runner 时，工具执行和历史回传由框架管理。直接调用 `ArkLlm.generateContent` 时，应将模型返回的完整 Content 加入历史，并在下一条 Content 中附上工具结果及对应 ID。调用和结果须放在不同 Content 中。旧历史没有 ID 时，仅支持按名称唯一配对；歧义或不匹配的结果会在请求发送前报错。

流式结果中的 `partial=true` 用于实时展示，最后一条聚合响应包含完整内容。累计用量时只读取最终响应。工具参数不是合法 JSON 对象时会报错；流中断或缺少结束标记时不会生成成功完成响应，调用方需要处理异常。

## 运行验证示例

仓库内的 `example/src/main/java/com/volcengine/veadk/example/basic/ArkInferenceExample.java` 使用本地示例工具，连续查询两个值，验证调用 ID 回传、两轮历史保留、最终回答及正数用量。它会产生真实模型请求和相应用量，不随单元测试自动运行。

先通过本地环境变量配置 `MODEL_AGENT_API_KEY`、`VEADK_TEST_MODEL`，可选 `MODEL_AGENT_API_BASE`。示例关闭深度思考，需要所选模型支持该设置及工具调用。使用 JDK 17，在仓库根目录运行：

```bash
./mvnw -B -ntp install -DskipTests
./mvnw -B -ntp -pl example exec:java \
  -Dexec.mainClass=com.volcengine.veadk.example.basic.ArkInferenceExample
./mvnw -B -ntp -pl example exec:java \
  -Dexec.mainClass=com.volcengine.veadk.example.basic.ArkInferenceExample \
  -Dexec.args=stream
```

若模型没有执行要求的工具调用或未返回用量，示例会明确失败。参数字段是否正确映射由自动化测试检查，真实模型示例验证接口接受配置以及工具调用闭环。

本批覆盖输入、输出与总用量，尚未映射缓存命中及推理 Token 明细。Responses API、结构化输出、模型 fallback 和 Planner 按后续批次交付。

## English

Ark Chat Completions now forwards temperature, top-p and output limits in both modes, preserves tool-call IDs and JSON results across turns, and exposes prompt/completion/total usage on responses and ADK events. Streaming usage is attached only to the final aggregate. Missing upstream usage stays absent; interrupted streams and malformed tool arguments surface errors.

Use `GenerateContentConfig` on the Agent or LlmRequest. When managing history directly, retain each returned Content and put tool results in a separate Content with the matching `FunctionResponse.id`. Legacy history without IDs is accepted only when a tool name identifies one pending call. Run `ArkInferenceExample` using the commands above; it makes live requests with environment-provided credentials and closes the client on completion. Responses API and detailed cached/reasoning usage are outside this batch.
