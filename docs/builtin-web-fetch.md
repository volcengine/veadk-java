# Built-in web fetch tool

`WebFetchTool` follows the `veadk-python` `web_fetch` contract. It performs a plain HTTP GET,
extracts readable HTML or PDF text, and does not execute JavaScript.

```java
var agent = Agent.builder()
        .name("reader")
        .model(new ArkLlm("your-model-endpoint"))
        .instruction("Use web_fetch to read public links before answering.")
        .tools(new WebFetchTool())
        .build();
```

The tool accepts these model-visible arguments:

- `url` (required): public `http` or `https` URL.
- `extract_mode`: `markdown` (default) or `text`.
- `max_chars`: result limit, default `50000` and hard-capped at `200000`.

Successful results contain `url`, `title`, `content`, and `truncated`. Failures contain `error`.
No tool-specific credentials are required.

The implementation blocks private, loopback, link-local, multicast, and reserved targets after DNS
resolution. HTTP and HTML meta-refresh redirects share a three-hop limit and every destination is
validated again. Requests time out after 30 seconds; HTML/text downloads are capped at 2 MB and PDF
downloads at 10 MB. Successful results are cached in process for 15 minutes.

The resolver validation and the HTTP connection are separate operations, matching the current
Python baseline. Deployments that accept attacker-controlled URLs should additionally enforce
egress policy at the network boundary to eliminate DNS-rebinding races.

For the AgentKit Java Runtime example, mount the tool by adding it to `AGENTKIT_TOOLS`:

```bash
export AGENTKIT_TOOLS=web_fetch
```
