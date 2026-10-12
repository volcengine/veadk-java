# AgentKit Runtime Java example

The example exposes a VeADK Java agent over the HTTP routes used by AgentKit
Simple App. It runs on JDK 17 and uses the existing `ArkLlm` and `Runner`.

This is a stateless, non-streaming application example. Each invocation gets a
fresh in-memory session. Authentication, persistent sessions, SSE, runtime
identity and platform deployment are outside this example. Local execution binds
to `127.0.0.1`; a container binds to `0.0.0.0` behind the platform gateway.

## Run locally

From the repository root:

```bash
./mvnw -B -ntp -DskipTests install
./mvnw -B -ntp -pl example dependency:copy-dependencies -DincludeScope=runtime

# Set MODEL_AGENT_API_KEY using your local secret-management mechanism.
export MODEL_AGENT_NAME="your-model-id"
# Optional: MODEL_AGENT_API_BASE overrides the Ark API base.
java -cp 'example/target/classes:example/target/dependency/*' \
  com.volcengine.veadk.example.agentkit.AgentKitRuntimeExample
```

`PORT` defaults to `8000`; `AGENTKIT_BIND_HOST` defaults to `127.0.0.1`.

```bash
curl --fail http://127.0.0.1:8000/ping
curl --fail http://127.0.0.1:8000/invoke \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Explain what VeADK does in one sentence."}'
```

`GET /ping` returns `{"status":"pong!"}`. `/health`, `/readiness` and `/liveness`
also support `GET`. Health routes check the HTTP process, not model availability.
`POST /invoke` accepts a JSON object with a non-empty `prompt` string and returns
a JSON string containing the agent's answer, matching a Simple App entrypoint
that returns a string. Invalid requests return `400`, bodies over 1 MiB return
`413`, and invocation failures return `500` without internal exception details.

## Build the container

After the Maven commands above, build from the generated artifacts only:

```bash
docker build -f example/agentkit/Dockerfile \
  -t veadk-agentkit-java:local example/target
docker run --rm -p 127.0.0.1:8000:8000 \
  --env MODEL_AGENT_NAME --env MODEL_AGENT_API_KEY --env MODEL_AGENT_API_BASE \
  veadk-agentkit-java:local
```

The image runs as UID/GID `10001` and exposes port `8000`. The build does not
embed model credentials. Supply environment variables when starting the container.

## Invoke with the official AgentKit CLI

With `agentkit-sdk-python==0.8.5` installed and the Java example running locally:

```bash
agentkit invoke --endpoint http://127.0.0.1:8000 \
  --apikey local-demo-placeholder "Reply with only the word READY."
```

The CLI requires an authentication argument for direct endpoints. The value
above is a placeholder for this local example, which does not implement
HTTP authentication. For a deployed runtime, use the gateway's actual
credentials and secret-management mechanism.

## AgentKit CLI compatibility boundary

The official CLI comes from
[`volcengine/agentkit-sdk-python`](https://github.com/volcengine/agentkit-sdk-python).
In version `0.8.5`, `agentkit init` and the local/cloud builders support Python
and Golang. Setting `language: Java` fails with `Unsupported language: Java`,
including when a custom Dockerfile exists. This example does not add Java to
that CLI and does not claim that `agentkit launch` can build this project.

The HTTP contract follows `agentkit/apps/simple_app/simple_app.py` and
`simple_app_handlers.py`; the local runner uses port `8000` and `/invoke` for
Basic App applications. Native CLI scaffolding and builds require changes in
the official CLI repository. Cloud deployment of this image has not been verified.

## Test

```bash
./mvnw -B -ntp -pl example -am \
  -Dtest=AgentKitRuntimeExampleTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The tests exercise real local HTTP requests with an injected handler, covering
health checks, Unicode JSON output, request validation, size limits, exact
routing, method checks and error redaction. They do not call a paid model.

---

# AgentKit Runtime Java 示例

该示例将 VeADK Java Agent 暴露为 AgentKit Simple App 使用的 HTTP 接口，
便于开发者和 SA 验证 Java 应用的启动、健康检查、模型调用与容器封装。
启动、构建和测试命令见上文。

本示例提供 `GET /ping`、`/health`、`/readiness`、`/liveness` 与 `POST /invoke`，
默认端口为 `8000`。调用请求为 `{"prompt":"你的问题"}`，响应为 JSON 字符串。
每个请求使用独立的内存会话，不保留跨请求历史。示例仅支持非流式调用；
认证、持久会话、SSE 和 Runtime Identity 需要业务应用另行接入。
本机默认只监听 `127.0.0.1`，容器监听 `0.0.0.0`，需由平台网关提供访问控制。

官方 AgentKit CLI 归属 `agentkit-sdk-python` 仓库。`0.8.5` 版本的初始化、
本地构建和云构建只支持 Python/Golang；即使提供自定义 Dockerfile，
`language: Java` 仍会在构建前被拒绝。此示例已提供 Java 应用的运行端适配，
CLI 原生 Java 脚手架与构建需要在官方 CLI 仓库继续补齐；云端部署尚未验证。
