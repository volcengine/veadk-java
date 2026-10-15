# Built-in tools

The Java SDK provides the seven built-in tool categories tracked by the capability-completion
plan. Their model-visible contracts follow `veadk-python`; Java-specific resource handling uses
ADK `BaseTool` and `BaseToolset` APIs.

| Category | Java entry point | Tool name / tools | Configuration |
|---|---|---|---|
| Web fetch | `WebFetchTool` | `web_fetch` | None |
| Parallel web search | `ParallelWebSearchTool` | `parallel_web_search` | Web-search AK/SK |
| Text to speech | `TextToSpeechTool` | `text_to_speech` | Seed TTS app ID and API key |
| PowerPoint | `PptGenerateTool` | `ppt_generate` | ADK artifact service |
| Lark | `LarkToolset.create()` | Tools exposed by official Lark MCP | Lark credentials and `npx` |
| Mobile use | `new MobileUseTool(systemPrompt)` | `mobile_use` | Volcengine AK/SK and cloud-phone IDs |
| Vanna + ClickHouse | `new VannaToolset(connectionString, storage)` | `run_sql`, `summarize_data`, `visualize_data` | JDBC connection string and storage path |

## Create tools

```java
import com.volcengine.veadk.tools.builtin.lark.LarkToolset;
import com.volcengine.veadk.tools.builtin.mobile.MobileUseTool;
import com.volcengine.veadk.tools.builtin.ppt.PptGenerateTool;
import com.volcengine.veadk.tools.builtin.tts.TextToSpeechTool;
import com.volcengine.veadk.tools.builtin.vanna.VannaToolset;
import com.volcengine.veadk.tools.builtin.webfetch.WebFetchTool;
import com.volcengine.veadk.tools.builtin.websearch.ParallelWebSearchTool;
import java.nio.file.Path;

var webFetch = new WebFetchTool();
var parallelSearch = new ParallelWebSearchTool();
var tts = new TextToSpeechTool();
var ppt = new PptGenerateTool();
var lark = LarkToolset.create();
var mobile = new MobileUseTool("Follow the user's instructions and use least privilege.");
var vanna = new VannaToolset(
        "clickhouse://user:password@host:8123/database?ssl=true",
        Path.of("/tmp/veadk-data"));
```

Close `McpToolset` instances such as the Lark toolset when the owning agent shuts down. PPT and
Vanna visualization results are saved through the current ADK artifact service when a tool context
is present.

## Environment variables

| Tool | Variables |
|---|---|
| Parallel web search | `TOOL_WEB_SEARCH_ACCESS_KEY`, `TOOL_WEB_SEARCH_SECRET_KEY`; falls back to `VOLCENGINE_ACCESS_KEY`, `VOLCENGINE_SECRET_KEY`, and optional `VOLCENGINE_SESSION_TOKEN` |
| TTS | `TOOL_VESPEECH_APP_ID`, `TOOL_VESPEECH_API_KEY`; optional `TOOL_VESPEECH_SPEAKER`, `TOOL_VESPEECH_AUDIO_OUTPUT_PATH` |
| Lark | `TOOL_LARK_ENDPOINT`, `TOOL_LARK_API_KEY`, `TOOL_LARK_TOKEN`; requires `npx` to launch `@larksuiteoapi/lark-mcp` |
| Mobile use | `VOLCENGINE_ACCESS_KEY`, `VOLCENGINE_SECRET_KEY`, optional `VOLCENGINE_SESSION_TOKEN`, and `TOOL_MOBILE_USE_TOOL_ID` as a JSON-like list of `productId-podId` values |

Vanna accepts `sqlite://`, `postgresql://`, `mysql://`, `clickhouse://`, and direct `jdbc:`
connection strings. Credentials parsed from a URL are passed as JDBC properties instead of being
retained in the JDBC URL. `run_sql` accepts one read-only statement, caps results at 10,000 rows,
and writes CSV output only below the configured storage directory. Local Python execution and
package installation are not performed inside the SDK process; use the sandbox toolset for code
execution.

See [Built-in web fetch](builtin-web-fetch.md) for web-fetch limits, redirect validation, SSRF
protection, and cache behavior. The AgentKit Java runtime example currently supports `web_fetch`
through `AGENTKIT_TOOLS`; configure the other tools directly on an agent when their credentials or
toolset lifecycle are required.
