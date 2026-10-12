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

## Invoke with the Python AgentKit CLI

With `agentkit-sdk-python==0.8.5` installed and the Java example running locally:

```bash
agentkit invoke --endpoint http://127.0.0.1:8000 \
  --apikey local-demo-placeholder "Reply with only the word READY."
```

The CLI requires an authentication argument for direct endpoints. The value
above is a placeholder for this local example, which does not implement
HTTP authentication. For a deployed runtime, use the gateway's actual
credentials and secret-management mechanism.

## AgentKit CLI integration

The TypeScript AgentKit CLI used for Java project delivery lives in
[`iaasng/agentkit-cli`](https://code.byted.org/iaasng/agentkit-cli). Its Java
lifecycle integration provides the `basic_java` template and Maven container
builds. Use a CLI build that includes that template; check `agentkit init --list-templates`.
The template is maintained in the CLI repository, while this SDK example
provides the runtime HTTP contract.

The Python `agentkit-sdk-python==0.8.5` CLI is a separate reference. Its
`init`/builders only support Python and Golang, but its direct `invoke` command
can call this Java service, as shown above. The HTTP contract follows
`agentkit/apps/simple_app/simple_app.py` and `simple_app_handlers.py`.
Cloud deployment of this SDK example has not been verified.

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

Java 项目交付使用的 TypeScript CLI 位于 `iaasng/agentkit-cli` 仓库。
其 Java 生命周期适配提供 `basic_java` 模板和 Maven 容器构建；需要使用包含
该模板的 CLI 版本。SDK 仓库保留 Runtime HTTP 示例，脚手架在 CLI 仓库维护。

Python `agentkit-sdk-python==0.8.5` CLI 是单独的协议参照，其初始化和构建仍仅
支持 Python/Golang，但可通过直接 `invoke` 调用 Java HTTP 服务。
云端部署尚未验证。
