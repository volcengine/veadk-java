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
```java
public class QuickstartAgentExample {
    public static void main(String[] args) {
        // Use an Ark model (replace with a model name available in your Ark Console)
        BaseAgent agent = LlmAgent.builder()
            .name("quickstart-agent")
            .instruction("You are a helpful assistant.")
            .model(new ArkLlm("doubao-seed-1-8-preview-251115"))
            .build();

        Runner runner = new Runner(agent);

        Session session = runner.sessionService()
            .createSession(runner.appName(), "userId", null, "sessionId")
            .blockingGet();

        // Build a simple conversation
        Content userMsg = Content.fromParts(Part.fromText("hello!"));
        RunConfig runConfig = RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.NONE).build();
        Flowable<Event> events = runner.runAsync(session.userId(), session.id(), userMsg, runConfig);

        // Print the final reply
        events.blockingForEach(e -> {
            if (e.finalResponse()) System.out.println(e.stringifyContent());
        });
    }
}
```

### Required Environment Variables
The example project requires the following environment variables before running (an explicit error is thrown if missing):

  - `MODEL_AGENT_API_KEY`: API Key for Volcengine Ark service (used by `ArkLlm`)
 
Example setup (macOS / Linux):

```bash
export MODEL_AGENT_API_KEY="<your-ark-api-key>"
```

## Run the Project Examples

### Local Knowledge and Long-term Memory

The backend-neutral local implementations do not require cloud credentials:

```java
KnowledgeBase knowledgeBase = new KnowledgeBase("docs");
knowledgeBase.addFromText("VeADK supports deterministic local retrieval.");

LongTermMemory longTermMemory = new LongTermMemory("my_app");
ShortTermMemory shortTermMemory = new ShortTermMemory();
Agent agent = Agent.builder()
    .name("memory-agent")
    .model(new ArkLlm("doubao-seed-1-8-preview-251115"))
    .knowledgebase(knowledgeBase)
    .shortTermMemory(shortTermMemory)
    .longTermMemory(longTermMemory)
    .build();

Runner runner = agent.newRunner();
```

Existing `VikingMemoryService` and `VikingKnowledgebaseService` entry points remain supported.

Ark LLM also accepts an ordered model list for Python-style fallbacks. The first model is tried
first; later models are only used when an attempt fails before any streaming output is emitted.

```java
ArkLlm model = new ArkLlm(List.of("doubao-seed-1-8-251228", "deepseek-r1-250528"));
```

ADK generation config values such as temperature, top-p, max output tokens, stop sequences,
penalties, candidate count, log probabilities and JSON/JSON-schema response formats are forwarded
to Ark requests when set. Text, inline image bytes, image URLs and video URLs from ADK `Part`
values are also mapped into Ark chat content parts. Tool-call history is round-tripped through Ark
assistant `tool_calls` and tool-result messages, including ids and parallel streaming tool calls.

To automatically copy completed sessions into long-term memory, enable `autoSaveSession` when a
long-term memory service is configured. The default policy matches Python's thresholds: first save
immediately, then save after 10 new events or 60 seconds, and flush the previous session when the
active session changes.

Direct construction of `SaveSessionToMemoryCallback()` keeps the original Java behavior: every
invocation starts a background save and returns immediately. Use the policy constructor when the
save must participate in the reactive callback chain.

```java
Agent agent = Agent.builder()
    .name("memory-agent")
    .model(new ArkLlm("doubao-seed-1-8-preview-251115"))
    .longTermMemory(longTermMemory)
    .autoSaveSession(true)
    .build();
```

For persistent local sessions, add the optional SQLite module:

```xml
<dependency>
    <groupId>com.volcengine.veadk</groupId>
    <artifactId>veadk-memory-sqlite</artifactId>
    <version>0.0.2</version>
</dependency>
```

```java
ShortTermMemory shortTermMemory = new ShortTermMemory(
    new SQLiteShortTermMemoryBackend(Path.of("./data/sessions.db")));
```

The core artifact does not transitively require the SQLite JDBC driver. SQLite appends reload the
canonical stored session before writing, so stale loaded views do not replace newer history.

### Build the Project
In the repository root, run: `./mvnw clean -DskipTests package`

After building, the compiled artifacts needed by the examples will be generated in `example/target`.

### Run the Example (CLI)
Entry class: `com.volcengine.veadk.example.AgentCliRunner`.

Run it (without modifying the POM, directly via Maven Exec plugin coordinates):

```bash
./mvnw -pl example -q compile exec:java -Dexec.mainClass=com.volcengine.veadk.example.AgentCliRunner
```

Interaction notes:
- After startup, follow the prompt to input messages and interact with `ArkAgent`.
- Type `quit` to exit.

### Run the Example (Web UI)
Start command:

```bash
./mvnw -pl example -q compile exec:java \
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
- If you need TLS Trace, besides AK/SK, also configure the TLS topic:
  - `OBSERVABILITY_OPENTELEMETRY_TLS_SERVICE_NAME`: ID of the TLS service trace log topic

## Related Projects
- Python version and documentation: [veadk-python](https://github.com/volcengine/veadk-python).

## FAQ
- Error on startup `Missing required configuration: <ENV_NAME>`: indicates a required environment variable is not set; please complete it according to the prompt.
- Unable to access memory/knowledgebase/search services: check AK/SK and network connectivity.
- Port occupied: if port `8000` is occupied, adjust via `--server.port` or in the startup parameters of `AdkWeb.main`.

## Security and privacy
This project takes security seriously.
For vulnerability reporting and supported versions, see [SECURITY.md](SECURITY.md)

## License
This project uses the Apache License 2.0; see the `LICENSE` file for details.
