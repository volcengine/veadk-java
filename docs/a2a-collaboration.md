# A2A collaboration

This module follows the public behavior of `veadk-python` at revision
`812faadfe9952d47d1efc7b0a32595beafa58b91` and uses Google ADK Java 1.11.0.

## A2A server and client

Expose an existing VeADK agent through the A2A REST transport:

```java
Agent agent = Agent.builder()
        .name("weather_agent")
        .description("Answers weather questions")
        .tools(weatherTool)
        .build();

VeA2AServer server = new VeA2AServer(
        agent,
        "http://127.0.0.1:8080",
        "weather_app",
        ShortTermMemory.local())
        .start("127.0.0.1", 8080);
```

Consume it as a normal ADK sub-agent:

```java
RemoteVeAgent remote = RemoteVeAgent.builder()
        .name("weather_agent")
        .url("http://127.0.0.1:8080")
        .authToken(System.getenv("A2A_TOKEN"))
        .authMethod("header") // or "querystring"
        .build();

Agent root = Agent.builder()
        .name("root_agent")
        .subAgents(remote)
        .build();
```

The default HTTP adapter applies a bounded 600-second connect/request timeout. A caller may pass a
custom A2A SDK `A2AHttpClient`. The server exposes the standard Agent Card, message send/stream,
task get, and task cancel REST routes.

## Dynamic sub-agents

Add `CreateAgentToolset` to the main agent. It exposes exactly `collect_resources` and
`create_agents`. Resource sources run concurrently, collection IDs are invocation-scoped, selected
resource refs are validated, repeated identical creation calls are idempotent, and successful
creation sets ADK's native `transfer_to_agent` action.

Dynamic blueprints support `llm`, `sequential`, `parallel`, and `loop` nodes. Every LLM leaf receives
the delegated task as runtime context and inherits the parent's configured model unless Java code
provides a separately managed resource. Created agents are unregistered and closed with the
toolset.

## AgentKit remote discovery

`A2ARegistrySearchTool` maps to Python's `a2a_registry_search_agent_cards`. It reads the same
registry Space environment aliases, limits `top_k` to 1–20, truncates prompts at a UTF-8 boundary to
2048 bytes, calls the signed AgentKit `SearchAgentCards` OpenAPI, and returns only safe name,
description, version, protocol and skill fields. Agent endpoint, authentication and security fields
are not returned to the model.

## A2A Hub

`A2AHubServer` and `A2AHubClient` implement the Python endpoints `/ping`, `/create_group`,
`/register_agent`, `/groups`, `/group/{id}/agents`, and `/group/{id}/agent/{agent}`. The client checks
server health on construction and can return all cards in a group or filter by agent ID.

## Java-specific differences

| Python baseline | Java behavior | Reason |
|---|---|---|
| Runtime names use `name__hash` | Runtime names use `name_hash` | ADK Java rejects consecutive separators in agent identifiers. |
| Dynamic leaf may select an arbitrary model string | Leaf inherits the already configured parent model | Java model construction also requires provider-specific credentials and ownership; silent construction would leak or double-close connections. |
| `workflow` nodes are available with ADK Python 2.x | Java supports classic `llm`/`sequential`/`parallel`/`loop` nodes | ADK Java 1.11.0 has no corresponding Workflow API. |
| `auth_method` can load a token from Python's invocation credential service | Java currently applies an explicitly supplied static token | ADK Java 1.11.0 has no equivalent A2A invocation credential-service hook. |
| Python tool source can compile Python at runtime | Java accepts injected `ResourceSource` objects containing Java tools/toolsets | Runtime Python execution is not part of the Java SDK. |

These differences are explicit; no unsupported option silently changes behavior.

## A2A execution lifecycle (development)

`VeA2AServer` now uses `VeA2AExecutor`, which can also be passed to the official A2A
JSON-RPC transport. Supply the same session, artifact and memory services as the
hosting application, and call `requestHandler(taskStore, queueManager, executor)`
to align the request handler timeout with the execution budget.

The executor creates the session with the explicit A2A context ID before ADK's
lookup. Repeated messages in that context retain history. It waits until the final
event is queued before returning from `execute`, so a slow model does not cause a
blocking `message/send` to return a prematurely closed `working` task. Supplied
agents and services remain caller-owned.

Timeout and interruption cancel the A2A task and release the waiting producer.
**Known release blocker:** ADK 1.11's `BaseLlmFlow` uses `Flowable.cache()`, which
retains the model subscription after downstream cancellation. This adapter cannot
force an already running model request to stop through ADK's public executor API.
Closing the shared agent would interrupt unrelated tasks, so the adapter does not
do that. `A2AExecutionLifecycleTest.timeoutCancelsModel` and
`interruptedProducerCancelsWorkAndRejectsDuplicateExecution` deliberately retain
the failing resource-release assertions. Task-state cancellation alone is not
proof that model work stopped.

Verification builds use a separately installed `0.0.3-SNAPSHOT`; the published
`0.0.2` artifact is not replaced. Do not release this development change as full
Python lifecycle parity until the cancellation defect is resolved.
