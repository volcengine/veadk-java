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

### RunCode Sandbox Example

`RunCodeAgent` exposes a separate `run_code_agent` application and registers only the
`run_code` tool. In AgentKit Runtime, bind a Sandbox tool to the Runtime so the platform injects
`AGENTKIT_TOOL_ID` and mounts its IAM role credential file.

For local or public-cloud execution, configure long-lived credentials explicitly:

```bash
export AGENTKIT_TOOL_ID="<your-sandbox-tool-id>"
export VOLCENGINE_ACCESS_KEY="<your-access-key>"
export VOLCENGINE_SECRET_KEY="<your-secret-key>"
```

For hybrid cloud, the Runtime normally injects these values automatically:

```text
AGENTKIT_TOOL_ID
AGENTKIT_TOOL_HOST
AGENTKIT_TOOL_REGION
FAAS_IAM_ROLE_CREDENTIAL_PATH
```

Hybrid-cloud tool hosts on port `8711` (or under `.vestack.cloud`) default to HTTP. Set
`AGENTKIT_TOOL_SCHEME` explicitly if the environment uses a different scheme. The IAM credential
file is read for every tool call so refreshed STS credentials are used.

After starting the ADK web server, create a session for `run_code_agent` and ask it to execute
code, for example: `Use Python and the run_code tool to calculate the 100th Fibonacci number.`

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
