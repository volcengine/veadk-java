# AgentKit Java runtime examples

The examples use VeADK Runner and existing ADK APIs on JDK 17. Local processes bind
to `127.0.0.1`; containers bind to `0.0.0.0`.

```bash
./mvnw -B -ntp -DskipTests install
./mvnw -B -ntp -pl example dependency:copy-dependencies -DincludeScope=runtime
# Supply MODEL_AGENT_API_KEY through your secret-management mechanism.
export MODEL_AGENT_NAME=your-model-id
java -cp 'example/target/classes:example/target/dependency/*' \
  com.volcengine.veadk.example.agentkit.AgentKitRuntimeExample
# For SSE, set AGENTKIT_STREAMING=true. For native ADK Web APIs, run:
java -cp 'example/target/classes:example/target/dependency/*' \
  com.volcengine.veadk.example.agentkit.AgentKitServerExample
```

```bash
docker build -f example/agentkit/Dockerfile -t veadk-agentkit-java:local example/target
docker run --rm -p 127.0.0.1:8000:8000 \
  --env MODEL_AGENT_NAME --env MODEL_AGENT_API_KEY --env AGENTKIT_STREAMING \
  veadk-agentkit-java:local
```

The image runs as UID/GID 10001. To run the server from the same image, override
its entrypoint with `--entrypoint java` and pass
`-cp '/app/app.jar:/app/lib/*' com.volcengine.veadk.example.agentkit.AgentKitServerExample`.

The TypeScript [AgentKit CLI](https://code.byted.org/iaasng/agentkit-cli) provides
`basic_java`, `basic_stream_java`, `agent_server_java` and Java `--from-agent` wrapping.
Use `agentkit init --list-templates` to check your installed CLI version.

```bash
agentkit invoke run --endpoint http://127.0.0.1:8000 --apikey local-placeholder \
  --headers '{"user_id":"alice","session_id":"demo"}' "Hello"
```

The placeholder is for local invocation; deployed runtimes use gateway credentials.
The separate Python CLI (`agentkit-sdk-python==0.8.5`) can call the basic HTTP
contract with `agentkit invoke --endpoint ... --apikey ... "Hello"`.

## Runtime contract

`GET /ping` returns `{"status":"pong!"}`; `/health`, `/readiness` and `/liveness`
check the HTTP process. `POST /invoke` accepts `{"prompt":"Hello"}`.
Basic mode returns a JSON string; streaming mode returns actual ADK events as SSE,
including partial model text and tool events. A stream failure after HTTP headers
is an `event: error` with a redacted `InvocationError` payload.

Send `user_id` and `session_id` headers to reuse a conversation. The user defaults
to `agentkit_user`; an omitted session ID creates a fresh session. IDs must match
`[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`. Sessions are in memory, scoped by user and lost
on restart. These headers are conversation selectors, not authenticated identity;
use a trusted gateway for access control.

Basic/stream mode has four invocation workers. Overlapping requests for the same
user/session return `409 SessionBusy`; a full worker pool returns `503`. Health
checks remain available. Invalid input returns `400`, bodies above 1 MiB return
`413`, and invocation failures before streaming return redacted `500` responses.
Disconnect detection on a subsequent stream write cancels the upstream subscription;
a stream with no further events times out after 120 seconds.

## Configuration

| Environment variable | Purpose |
| --- | --- |
| `MODEL_AGENT_NAME` | Required accessible Ark model ID for the built-in agent |
| `MODEL_AGENT_API_KEY`, `MODEL_AGENT_API_BASE` | Model credentials and optional endpoint |
| `AGENT_NAME`, `AGENT_DESCRIPTION`, `MODEL_AGENT_INSTRUCTION` | Agent metadata and instruction |
| `AGENTKIT_TOOLS` | Comma-separated `web_search,run_code`; unsupported names fail startup |
| `AGENTKIT_STREAMING` | `true` enables SSE in the basic runtime |
| `AGENTKIT_AGENT_CLASS` | Existing class with `public static BaseAgent ROOT_AGENT` |
| `AGENTKIT_BIND_HOST`, `PORT` | Bind address and port (container default: `0.0.0.0:8000`) |

Built-in tools use the SDK's existing configuration. `run_code` requires
`VOLCENGINE_ACCESS_KEY`, `VOLCENGINE_SECRET_KEY` and `TOOL_CODE_SANDBOX_URL`, plus
access to that sandbox. `web_search` uses `VOLCENGINE_ACCESS_KEY` and `VOLCENGINE_SECRET_KEY`
and needs Web Search service permissions. Selecting a tool does not provision its backing service.
An existing ROOT_AGENT owns its model, tools and instructions; configure those in
its Java code. The wrapper closes the agent on process shutdown.

## Basic Agent Server

The server uses ADK Web 1.9.0 and exposes `/list-apps`, `/run`, `/run_sse`, and
`/apps/{app}/users/{user}/sessions` (create/list/get/delete). Its `/invoke` adapter
accepts the same prompt/context headers and streams events. This mode uses ADK
Web's session and execution behavior, not the basic runtime's 409/503 limits.
The native endpoints are intended for a trusted gateway/network. Configure
`adk.web.cors.origins` for browser access. Server Spring configuration uses the
`agentkit` basename (for example, `agentkit.properties`). This is not full Python Agent Server
parity: A2A, Runtime Identity/credential integration and Harness are not provided.

## Verification

```bash
./mvnw -B -ntp clean test
```

Tests execute real HTTP sockets and a Spring server. They cover session reuse and
isolation, duplicate/invalid headers, same-session overlap, overload/health behavior,
partial SSE before completion, redacted stream failure, disconnect cancellation,
ROOT_AGENT validation, tool configuration, native session CRUD and both invocation APIs.
Model-independent tests do not make paid calls. Real Ark/container verification is
recorded separately; cloud deployment and external Web Search/sandbox permissions
have not been certified by these local tests.
