# Python to Java parity matrix

Reference revisions are recorded in [BASELINE.md](BASELINE.md). Status and implementation notes
describe the current Java working tree relative to that pinned baseline.

| Area | Python reference | Current Java implementation | Status | Priority | Required Java outcome |
| --- | --- | --- | --- | --- | --- |
| Compatibility/build | `pyproject.toml`, Python CI/tests | Reflection API guard, legacy-null overload compile guard, portable Mockito agent, Spotless, JaCoCo, 130 green tests across four modules and legacy example compilation | Partial | P0 | Add binary API comparison and JDK CI matrix |
| Configuration | `config.py`, `configs/*`, `.env`, `config.yaml` | Typed YAML/dotenv/environment loading, precedence, BytePlus aliases, defaults and secret redaction | Complete | P0 | Maintain aliases as new domains are added |
| Agent facade | `agent.py` | VeADK `Agent` extends `LlmAgent`; builder supports model, tools, sub-agents, memories, plugins, processors, skills and metadata | Partial | P0 | Add tracing/prompt interfaces and remaining Python opt-in features |
| Runner | `runner.py`, runner contract tests | Old constructors preserved; injectable services/plugins and run processors added; direct `new Runner(agent)` inherits a VeADK Agent's configured memory, plugins and processor | Partial | P0 | Add convenience streaming/session helpers and multimodal messages |
| Multi-agent primitives | `agents/{loop,parallel,sequential,supervise}_agent.py`, `flows/*` | Google ADK primitives only | Missing | P0 | VeADK wrappers and supervisor flows with contract tests |
| Agent metadata/search | `agent_metadata.py`, `agent_search.py` | Stable recursive metadata for agents, models, tools, components, skills, sources and topology; backend search execution pending | Partial | P0 | Add knowledge/memory search adapters after neutral SPIs |
| Ark LLM | `models/ark_llm.py` and context/fallback tests | Chat Completions adapter with effective primary-model fallback, streaming and parallel tool calls, tool-call history round-trip, generation parameters/logprobs, typed and raw JSON schemas, image/video input parts, finishReason and usage metadata | Partial | P0 | Preserve old constructors; align Responses API caching and remaining Chat Completions edge cases |
| Ark embedding | `models/ark_embedding.py` | Ark multimodal text embeddings with sync/reactive/batch APIs, dimensions, usage, model enum and credential fallback | Complete | P0 | Extend only when Python adds new input modalities/contracts |
| Knowledge-base facade | `knowledgebase/{knowledgebase,entry,types}.py` | Backend-neutral facade/SPI, text/file/directory ingestion, deterministic search and legacy service adapter | Complete | P0 | Maintain facade contracts while adding external backends |
| Knowledge-base backends | `knowledgebase/backends/*` | In-memory and existing Viking service adapter | Partial | P1 | Add Redis, Milvus, OpenSearch, OpenViking, TOS and context-search adapters |
| Long-term memory | `memory/long_term_memory.py`, backend SPI | ADK-compatible facade, event filtering/conversion, backend SPI, graceful search errors and legacy service adapter | Complete | P0 | Maintain facade contracts while adding external backends |
| Long-term-memory backends | `memory/long_term_memory_backends/*` | Deterministic per-user in-memory backend; Viking service also implements the new SPI | Partial | P1 | Add Mem0, OpenSearch, OpenViking, Redis and TOS adapters |
| Short-term memory | `memory/short_term_memory.py`, processor and backends | ADK-compatible facade/SPI, idempotent creation, load callback, history processor, canonical-history append, in-memory backend, service adapter and optional persistent SQLite module with stale-view protection | Partial | P0 | Add profile-based compaction; MySQL/PostgreSQL remain optional adapters |
| Save-session behavior | `memory/save_session_callback.py` | Python-compatible policy with first-save, thresholds and session-switch flush; per-user saves are serialized and awaited, Agent-bound memory is honored, while the zero-argument constructor preserves legacy fire-and-forget behavior | Complete | P0 | Maintain callback compatibility as memory contracts evolve |
| Built-in tools | `tools/builtin_tools/*` | Knowledge-base, web-search and run-code tools | Partial | P1 | Registry plus compatible implementations for applicable Python built-ins |
| MCP tools | `tools/mcp_tool/*` | Raw Google ADK MCP toolset used by sandbox | Partial | P1 | Trusted session manager/toolset, auth propagation, retry and lifecycle contracts |
| Sandbox tools | `tools/sandbox/*` | Code Sandbox MCP and AgentKit run-code | Partial | P1 | Code, browser and computer sandbox contracts with optional implementations |
| Skills | `skills/*`, `tools/skills_tools/*` | Missing | Missing | P1 | Parser, registry, materializer, file safety, toolset and checklist callback |
| Vanna/data tools | `tools/vanna_tools/*` | Missing | Missing | P2 | Optional data-analysis module and backend-neutral SQL/tool contracts |
| Authentication | `auth/*`, `integrations/ve_identity/*` | AK/SK passed directly to wrappers | Partial | P1 | Credential service, VeAuth providers, OAuth middleware and identity-aware tools |
| A2A | `a2a/*` | Missing | Missing | P1 | Agent card, executor, server, task store, registry client and middleware |
| A2UI | `a2ui/*` | Missing | Missing | P2 | Catalog and send-to-client toolset with optional dependency boundary |
| AgentKit application | `integrations/agentkit/app.py` | AgentKit tool invocation wrapper only | Partial | P1 | Application factory/server, health, metadata, topology and component summaries |
| AgentKit evaluation/feedback | `integrations/agentkit/evaluation/*` | Missing | Missing | P2 | Evaluation client, idempotent feedback and session capabilities |
| Evaluation | `evaluation/*` | Missing | Missing | P1 | Evaluator SPI, ADK evaluator, dataset loading/recording and Java-native metric adapters |
| Prompt management | `prompts/*`, PromptPilot integration | Missing | Missing | P1 | Prompt manager, evaluation, memory processor and optional PromptPilot adapter |
| Run processors | `processors/*` | Composable deferred event-stream processor, immutable run context and no-op default | Complete | P0 | Add domain-specific processors in their feature batches |
| Tracing/telemetry | `tracing/*` | OTel initialization, TLS and attribute rewriting | Partial | P1 | Preserve current API; add content policy, attributes, metrics and exporter SPI |
| Extensions/harness | `extensions/harness/*` | Missing | Missing | P2 | Extension lifecycle, invocation context, compaction and response verification |
| Feishu channel | `extensions/feishu_channel.py` | Missing | Missing | P2 | Runner/channel adapter with stable conversation/session mapping |
| Multimodal | `multimodal/*` | Missing | Missing | P2 | Attachment models, storage, transport, service and AgentKit routes |
| Realtime voice | `realtime/*` | Missing | Missing | P2 | Realtime protocol/client and Doubao voice model connection |
| Alternative runtimes | `runtime/{codex,piagent}/*` | Google ADK runtime only | Missing | P2 | Runtime SPI plus optional runtime bridges; default remains ADK |
| Tunnel | `tunnel/*` | Missing | Missing | P2 | Connector, registry, server protocol and MCP toolset |
| CLI/Studio | `cli/*`, `frontend/*`, `webui/*` | CLI example and Google ADK dev web dependency | Missing | P2 | Java CLI, generated project flow, runtime management APIs and reusable web assets |
| Cloud integrations | `cloud/*`, `integrations/ve_*` | Direct Volcengine wrappers for three services | Partial | P2 | Optional deployment/container/FaaS/pipeline/TOS/TLS integrations |
| Audio/dataset toolkits | `toolkits/*` | Missing | Missing | P2 | Optional ASR/TTS clients and dataset-generation callback |

## Completion rule

Every row must eventually be `Complete` or carry a documented, reviewed Java-specific exclusion.
Priority controls implementation order only; it does not remove P2 features from the parity goal.
