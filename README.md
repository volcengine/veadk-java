# veadk-java

An open-source Agent development toolkit that integrates the powerful capabilities of Volcengine.

## Quick Start

### Requirements
- JDK `17` or above

```xml
<dependency>
    <groupId>com.volcengine.veadk</groupId>
    <artifactId>veadk-java</artifactId>
    <version>0.0.2</version>
</dependency>
```

### Agent

Use `Agent.builder()` as the VeADK Java entry point. It keeps the ADK Java
`LlmAgent` execution path, while adding VeADK defaults and metadata that
integrations can read without reflection.

```java
import com.volcengine.veadk.Agent;

Agent agent = Agent.builder()
    .name("quickstart-agent")
    .description("Answers user questions.")
    .instruction("You are a helpful assistant.")
    .model("doubao-seed-2-1-pro-260628")
    .modelApiKey(System.getenv("MODEL_AGENT_API_KEY"))
    .build();
```

Without an explicit provider, VeADK follows veadk-python and uses the
OpenAI-compatible path by default. Configure the API key with `modelApiKey(...)`,
or leave it unset to read `MODEL_AGENT_API_KEY` from the environment. The
default base URL is Ark's OpenAI-compatible endpoint; use `modelApiBase(...)` /
`modelBaseUrl(...)` when you need OpenAI official, LiteLLM Proxy, or another
compatible gateway.

OpenAI-compatible endpoints are configured the same way, without requiring
application code to construct an ADK `BaseLlm`:

```java
Agent agent = Agent.builder()
    .name("openai-agent")
    .instruction("You are a helpful assistant.")
    .model("openai/gpt-4o")
    .modelApiKey(System.getenv("OPENAI_API_KEY"))
    .modelBaseUrl("https://api.openai.com/v1")
    .build();
```

Use `modelProvider("ark")` only when you want the Ark-specific `ArkLlm` adapter:

```java
Agent agent = Agent.builder()
    .name("ark-agent")
    .modelProvider("ark")
    .model("doubao-seed-2-1-pro-260628")
    .modelApiKey(System.getenv("MODEL_AGENT_API_KEY"))
    .modelApiBase("https://ark.cn-beijing.volces.com/api/v3")
    .build();
```

For LiteLLM Proxy or an internal OpenAI-compatible gateway, set the base URL to
that endpoint. Use `modelProvider("openai")` when the model name itself contains
provider routing text:

```java
Agent agent = Agent.builder()
    .name("litellm-agent")
    .modelProvider("openai")
    .model("anthropic/claude-sonnet-4")
    .modelApiKey(System.getenv("LITELLM_API_KEY"))
    .modelBaseUrl("http://localhost:4000/v1")
    .build();
```

You can also provide an explicit ADK `BaseLlm` instance when you want to own the
model configuration yourself:

```java
import com.google.adk.models.BaseLlm;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.model.ArkLlm;

BaseLlm model = new ArkLlm("doubao-seed-2-1-pro-260628");

Agent agent = Agent.builder()
    .name("custom-model-agent")
    .instruction("You are a helpful assistant.")
    .model(model)
    .build();
```

Metadata is available through a typed extractor. For VeADK `Agent` instances it
uses `Agent.metadataSnapshot()`; for plain ADK `LlmAgent` instances it falls
back to public ADK getters.

```java
import com.volcengine.veadk.AgentMetadata;
import com.volcengine.veadk.AgentMetadataExtractor;

AgentMetadata metadata = AgentMetadataExtractor.extract(agent);
System.out.println(metadata.tools());
```

For a simple blocking interaction, use the VeADK runner convenience API:

```java
import com.volcengine.veadk.Runner;

String answer = new Runner(agent).run("Hello");
```

Short-term memory is session-scoped context. Configure it on the agent, then
reuse the same `userId` and `sessionId` when running follow-up turns:

```java
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.memory.ShortTermMemory;

ShortTermMemory shortTermMemory = ShortTermMemory.builder().local().build();

Agent agent = Agent.builder()
    .name("memory_agent")
    .instruction("Remember what the user tells you.")
    .modelName("doubao-seed-2-1-pro-260628")
    .shortTermMemory(shortTermMemory)
    .build();

Runner runner = new Runner(agent, "memory_demo");

runner.run("user_1", "session_1", "My name is Ming.");
runner.run("user_1", "session_1", "What is my name?");
```

The extracted metadata includes:

- Basic agent fields: `id`, `name`, `description`, `instructionSummary`, `modelName`,
  and `autoSaveSession`.
- `tools`: tool names with their source. `explicit` means the user passed the
  tool through `Agent.builder().tools(...)`; `auto` means the builder injected it
  from a configured VeADK component, such as `loadKnowledgebase` or `loadMemory`;
  `adk` is used when extracting from a plain ADK `LlmAgent`.
- `subAgents`: the same metadata shape for each child agent.
- `components`: stable component slots for `knowledgebase`, `longTermMemory`,
  `shortTermMemory`, `tracer`, `toolset`, and `plugin`.
- `searchSources`: `web`, `knowledge`, and `memory`, each with an `enabled`
  flag and the associated tool name when applicable. The canonical tool names
  are `web_search`, `loadKnowledgebase`, and `loadMemory`.

### Required Environment Variables
Examples that auto-create a model without an explicit API key require the
following environment variable before running (an explicit error is thrown if
missing):

  - `MODEL_AGENT_API_KEY`: default API key for auto-created model adapters
 
Example setup (macOS / Linux):

```bash
export MODEL_AGENT_API_KEY="<your-ark-api-key>"
```

## Run the Project Examples

### Build the Project
In the repository root, run: `./mvnw clean -DskipTests package`

After building, the compiled artifacts needed by the examples will be generated in `example/target`.

Run the Agent example. It builds an Ark-backed `Agent`, registers a Java
function tool, and calls `Runner.run(...)` directly:

```bash
export MODEL_AGENT_API_KEY="<your-ark-api-key>"
./mvnw -pl example -am -q compile exec:java -Dexec.mainClass=com.volcengine.veadk.example.AgentExample
```

### Run the Example (CLI)
Entry class: `com.volcengine.veadk.example.AgentCliRunner`.

Run it (without modifying the POM, directly via Maven Exec plugin coordinates):

```bash
./mvnw -pl example -am -q compile exec:java -Dexec.mainClass=com.volcengine.veadk.example.AgentCliRunner
```

Interaction notes:
- After startup, follow the prompt to input messages and interact with `ArkAgent`.
- Type `quit` to exit.

### Run the Example (Web UI)
Start command:

```bash
./mvnw -pl example -am -q compile exec:java \
    -Dexec.mainClass="com.google.adk.web.AdkWebServer" \
    -Dexec.args="--adk.agents.source-dir=example/target --server.port=8000"  
```

- Access URL: `http://localhost:8000`

### Run in IDE
- Import the Maven multi-module project using IntelliJ IDEA or Eclipse.
- Directly run the `main` method of `AgentCliRunner` or `AdkWeb`.
- Ensure the required environment variables are injected in your IDE run configuration (or start the IDE from a shell that has them set).
- If you need `web search`, `Viking Memory`, or `Viking Knowledgebase`, configure these environment variables:
  - `VOLCENGINE_ACCESS_KEY`: Volcengine AccessKey
  - `VOLCENGINE_SECRET_KEY`: Volcengine SecretKey
- If you need `Mem0 Memory`, configure either a direct Mem0 API key:
  - `DATABASE_MEM0_API_KEY`: Mem0 API Key
  - `DATABASE_MEM0_BASE_URL`: Mem0 endpoint, for example `https://api.mem0.ai`
- Or configure Volcengine credentials and a Mem0 project/API key id so VeADK can resolve the Mem0 API key:
  - `VOLCENGINE_ACCESS_KEY`: Volcengine AccessKey
  - `VOLCENGINE_SECRET_KEY`: Volcengine SecretKey
  - `REGION`: Volcengine region, defaults to `cn-beijing`
  - `DATABASE_MEM0_PROJECT_ID`: Mem0 memory project id
  - `DATABASE_MEM0_API_KEY_ID`: optional Mem0 API key id
- If you need TLS Trace, besides AK/SK, also configure the TLS topic:
  - `OBSERVABILITY_OPENTELEMETRY_TLS_SERVICE_NAME`: ID of the TLS service trace log topic

Run the Mem0 memory example:

```bash
./mvnw -q install -DskipTests
./mvnw -pl example -am -q compile exec:java -Dexec.mainClass=com.volcengine.veadk.example.Mem0MemoryAgent
```

## Related Projects
- Python version and documentation: [veadk-python](https://github.com/volcengine/veadk-python).

## Current Scope

The Java Agent intentionally starts with a small, typed contract. It matches the
Python package at the user-entry level (`Agent.builder()`, model, tools,
sub-agents, memory/knowledgebase metadata), while Java records metadata from
explicit builder state and ADK public accessors instead of dynamically scanning
object internals.

PR0 does not yet support these Python-side capabilities:

- `runtime=codex/piagent`
- `enableResponses`
- legacy `skills` / `skillsMode`
- `enableA2ui`
- `enableTunnel`
- YAML or dynamic tool discovery

## FAQ
- Error on startup `Missing required configuration: <ENV_NAME>`: indicates a required environment variable is not set; please complete it according to the prompt.
- Unable to access memory/knowledgebase/search services: check AK/SK and network connectivity.
- Port occupied: if port `8000` is occupied, adjust via `--server.port` or in the startup parameters of `AdkWeb.main`.

## Security and privacy
This project takes security seriously.
For vulnerability reporting and supported versions, see [SECURITY.md](SECURITY.md)

## License
This project uses the Apache License 2.0; see the `LICENSE` file for details.
