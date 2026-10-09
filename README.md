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
    .modelName("doubao-seed-2-1-pro-260628")
    .modelApiKey(System.getenv("MODEL_AGENT_API_KEY"))
    .build();
```

`modelName(...)` creates a default `ArkLlm` at build time. Configure the Ark API
key with `modelApiKey(...)`, or leave it unset to resolve credentials from the
environment. Use `modelApiBase(...)` / `modelBaseUrl(...)` when you need a
custom Ark endpoint.

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

#### Local Skills

Local skills are supported through ADK Java's `SkillToolset`. When
`Agent.builder().skills(...)` is configured, VeADK Java automatically injects
one `SkillToolset` unless the user already passed an explicit `SkillToolset`
through `tools(...)`.

A skill is a directory containing a required `SKILL.md` file:

```text
skills/
  expense-policy-reviewer/
    SKILL.md
    references/
    assets/
    scripts/
```

The `SKILL.md` file must start with frontmatter whose `name` matches the skill
directory name:

```markdown
---
name: expense-policy-reviewer
description: Review employee reimbursement requests against the company policy.
---

Follow the reimbursement policy and produce a concise review.
```

Pass a skills root directory, a single skill directory, a `SKILL.md`/`skill.md`
file, or an explicit ADK `SkillSource`:

```java
import java.nio.file.Path;

Agent agent = Agent.builder()
    .name("expense-review-agent")
    .instruction("Use local skills before answering policy questions.")
    .modelName("doubao-seed-2-1-pro-260628")
    .skills(Path.of("example/src/main/resources/skills"))
    .skillsMode("local")
    .build();
```

Supported entries for `skills(...)` are:

- `Path` or `String`: local filesystem path. A directory with `SKILL.md` or
  `skill.md` is treated as one skill; otherwise the directory is treated as a
  skills root containing skill subdirectories.
- `SkillSource`: any ADK Java skill source, including `LocalSkillSource` or
  `ClassPathSkillSource`.

Multiple skill entries are merged in the order provided. If more than one entry
exposes the same skill name, the later entry takes precedence for both
`list_skills` and `load_skill`.

For skills packaged in application resources, pass ADK's classpath source
explicitly:

```java
import com.google.adk.skills.ClassPathSkillSource;

Agent agent = Agent.builder()
    .name("classpath-skill-agent")
    .skills(new ClassPathSkillSource("skills"))
    .skillsMode("local")
    .build();
```

Only `skillsMode("local")` is currently supported. Sandbox-backed Python modes
such as `skills_sandbox` and `aio_sandbox` intentionally fail fast until a typed
Java sandbox design is added.

The extracted metadata includes:

- Basic agent fields: `id`, `name`, `description`, `instructionSummary`, `modelName`,
  and `autoSaveSession`.
- `tools`: tool names with their source. `explicit` means the user passed the
  tool through `Agent.builder().tools(...)`; `auto` means the builder injected it
  from a configured VeADK component, such as `loadKnowledgebase`, `loadMemory`,
  or `SkillToolset`; `adk` is used when extracting from a plain ADK `LlmAgent`.
- `subAgents`: the same metadata shape for each child agent.
- `components`: stable component slots for `knowledgebase`, `longTermMemory`,
  `shortTermMemory`, `tracer`, `toolset`, and `plugin`.
- `searchSources`: `web`, `knowledge`, and `memory`, each with an `enabled`
  flag and the associated tool name when applicable. The canonical tool names
  are `web_search`, `loadKnowledgebase`, and `loadMemory`.

### Model Credentials
Examples that instantiate or call `ArkLlm` need model credentials. The
resolution order is:

- `modelApiKey(...)`: explicit API key on the builder.
- `MODEL_AGENT_API_KEY`: raw Ark API key from the environment.
- `MODEL_AGENT_API_KEY_NAME`: Ark API key name. When set, VeADK resolves the raw
  key through Ark OpenAPI.
- Volcengine AK/SK fallback: when no key value or key name is configured, VeADK
  resolves the first Ark API key in the account through Ark OpenAPI.

Ark OpenAPI fallback requires:

- `VOLCENGINE_ACCESS_KEY`
- `VOLCENGINE_SECRET_KEY`
- Optional: `VOLCENGINE_SESSION_TOKEN` or `VOLC_SESSIONTOKEN`
- Optional: `REGION`, defaulting to `cn-beijing`
- Optional: `CLOUD_PROVIDER=byteplus` for BytePlus control-plane routing
 
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

Run the local skills example. It loads a reimbursement policy skill from
`example/src/main/resources/skills` and pre-reviews a realistic expense claim:

```bash
export MODEL_AGENT_API_KEY="<your-ark-api-key>"
./mvnw -pl example -am -q compile exec:java -Dexec.mainClass=com.volcengine.veadk.example.LocalSkillsExpenseReviewAgent
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

## Supported Feature Scope

The Java Agent provides a small, typed contract. It matches the Python package
at the user-entry level (`Agent.builder()`, model, tools, sub-agents,
memory/knowledgebase metadata), while Java records metadata from explicit
builder state and ADK public accessors instead of dynamically scanning object
internals.

The Java Agent does not currently support these Python-side capabilities:

- `runtime=codex/piagent`
- `enableResponses`
- sandbox-backed `skillsMode` values such as `skills_sandbox` or `aio_sandbox`
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
