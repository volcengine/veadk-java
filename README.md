# veadk-java

An open-source Agent development toolkit that integrates the powerful capabilities of Volcengine.

## Quick Start

### Requirements
- JDK `17` or above

```xml
<dependency>
    <groupId>com.volcengine.veadk</groupId>
    <artifactId>veadk-java</artifactId>
    <version>0.0.1</version>
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
- If you need `web search`, Viking resource management, or the existing AK/SK path for `Viking Memory` and `Viking Knowledgebase`, configure these environment variables:
  - `VOLCENGINE_ACCESS_KEY`: Volcengine AccessKey
  - `VOLCENGINE_SECRET_KEY`: Volcengine SecretKey
- Viking data-plane operations also support dedicated API keys for collections that already exist:
  - `DATABASE_VIKING_API_KEY`: search a Viking Knowledgebase collection
  - `DATABASE_VIKINGMEM_API_KEY`: add and search Viking Memory
  - `KnowledgeBase.Builder.apiKey(String)` and `VikingMemoryService(String, String)` provide per-instance explicit values. A non-blank explicit value takes precedence over the matching environment variable; blank, `none`, and `null` values fall back to the environment.
  - With only an API key configured, initialization skips collection existence checks and automatic creation. Collection and document management (including Knowledgebase `addDoc`) still require both `VOLCENGINE_ACCESS_KEY` and `VOLCENGINE_SECRET_KEY`. API key requests do not fall back to AK/SK after an authentication or service failure.

Example with placeholder API keys and pre-existing collections:

```java
KnowledgeBase knowledgeBase =
        KnowledgeBase.builder()
                .backend("viking")
                .appName("existing_knowledge_collection")
                .apiKey("YOUR_VIKING_KNOWLEDGEBASE_API_KEY")
                .build();
VikingMemoryService memoryService =
        new VikingMemoryService(
                "existing_memory_collection", "YOUR_VIKING_MEMORY_API_KEY");
```
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
./mvnw -pl example -q compile exec:java -Dexec.mainClass=com.volcengine.veadk.example.Mem0MemoryAgent
```

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
