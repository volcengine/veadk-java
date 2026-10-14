# Identity、LLM Shield 与工具授权

本实现以 `veadk-python@812faadfe9952d47d1efc7b0a32595beafa58b91` 为行为基线，使用 Google ADK Java 1.9.0 的回调接口接入，不改变 ADK 执行器。

## 接入

```java
IdentityClient identityClient = HttpIdentityClient.fromEnvironment();
IdentityAuthorization authorization = new IdentityAuthorization(identityClient);
IdentityCredentials credentials = new IdentityCredentials(identityClient);

LlmShieldClient shieldClient = HttpLlmShieldClient.fromEnvironment();
LlmShieldGuardrail shield = new LlmShieldGuardrail(
        shieldClient,
        LlmShieldGuardrail.FailureMode.ALLOW,
        Set.of()); // 空集合表示审核所有工具

AgentSecurity security = new AgentSecurity(authorization, shield);
Agent agent = Agent.builder()
        .beforeAgentCallback(security.beforeAgentCallback())
        .beforeModelCallback(security.beforeModelCallback())
        .afterModelCallback(security.afterModelCallback())
        .beforeToolCallback(security.beforeToolCallback())
        .afterToolCallback(security.afterToolCallback())
        .build();
```

工具实现可通过 `IdentityCredentials.workloadToken(...)`、`apiKey(...)` 或 `oauth2(...)` 获取受管凭证，再注入实际的出站请求。API Key 按 Agent、用户、Provider 缓存；OAuth2 用户联邦可能返回 `AUTHORIZATION_URL`，调用方应展示给用户完成授权。

`AgentSecurity` 会先执行工具权限检查；拒绝时不会调用 LLM Shield 或实际工具。通过后再审核工具参数，工具执行后审核结果。

## 配置

Identity 使用以下环境变量：

- `VOLCENGINE_ACCESS_KEY`、`VOLCENGINE_SECRET_KEY`，可选 `VOLCENGINE_SESSION_TOKEN`
- `VEIDENTITY_REGION`，回退到 `REGION`，默认 `cn-beijing`
- `IDENTITY_OPENAPI_HOST`，默认 `open.volcengineapi.com`
- `IDENTITY_OPENAPI_SCHEME`，默认 `https`
- `RUNTIME_IDENTITY_NAMESPACE`，默认 `default`
- `CLOUD_PROVIDER=byteplus` 时使用 `BYTEPLUS_ACCESS_KEY`、`BYTEPLUS_SECRET_KEY`、`BYTEPLUS_SESSION_TOKEN`（可回退到 Volcengine 变量），默认端点为 `id.{region}.byteplusapi.com`

LLM Shield 使用以下环境变量：

- `TOOL_LLM_SHIELD_APP_ID`
- `TOOL_LLM_SHIELD_API_KEY`；未配置时使用上述 AK/SK
- `TOOL_LLM_SHIELD_REGION`，回退到 `REGION`，默认 `cn-beijing`
- `TOOL_LLM_SHIELD_URL`，默认 `https://{region}.sdk.access.llm-shield.omini-shield.com`

Identity 工作负载令牌按 `workload_token:{agent}:{user}` 缓存在 ADK Session state 中，并在到期前 60 秒刷新。JWT 只用于解析 Identity 已签发令牌里的 `sub` 与嵌套 `act` 委托链，不被当作本地认证验证。

## Python 对齐与 Java 差异

- 对齐：Agent 权限的主体、`invoke` 操作、Agent 资源与原始调用链；授权拒绝或异常均 fail-closed。
- 对齐：支持工作负载令牌、受管 API Key，以及返回 Access Token 或 Authorization URL 的 OAuth2 流程。
- 对齐：LLM Shield 审核最后一条用户消息、模型输出、工具参数与工具结果；风险文案和 101/103/104/106/107 分类一致。
- 对齐：Shield 网络、配置或响应异常默认 `FailureMode.ALLOW`，保持 Python fail-open；Java 额外提供 `BLOCK`。
- Java 扩展：实现 User → Agent → Tool 的工具级权限检查，工具资源 ID 使用 `BaseTool.name()`；当前 Python 仅直接接入 Agent 检查。
- Java 扩展：`protectedTools` 可只审核指定工具；空集合保持 Python 的“审核全部工具”。
- 当前 Java 客户端只支持标准 `/v2/moderate` 接口，不支持 Python 中私有 Lumen `/OpenTOP/V1/Lumen/Moderate` 分支。
