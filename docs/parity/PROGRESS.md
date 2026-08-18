# Parity progress

## Completed

- [x] Clone and pin the Java and Python repositories.
- [x] Verify both worktrees started clean.
- [x] Create the `codex/python-parity` implementation branch.
- [x] Inventory Java source, tests, public APIs, Maven configuration and examples.
- [x] Inventory Python feature domains, dependencies and test domains.
- [x] Record baseline, compatibility policy, architecture decisions and initial parity matrix.
- [x] Establish a green baseline: 73 tests, three Maven modules, examples and packaging.
- [x] Add public API compatibility characterization tests.
- [x] Make Mockito tests portable to restricted CI environments.
- [x] Add typed `config.yaml`/`.env`/environment loading with BytePlus aliases and secret redaction.
- [x] Add a VeADK `Agent` facade over `LlmAgent` with memory, plugins and processor assembly.
- [x] Add injectable Runner services and a composable run-processor lifecycle.
- [x] Add recursive Agent metadata, tools, components, skills, search sources and topology.
- [x] Add Ark multimodal text embeddings with sync/reactive/batch APIs, dimensions and usage.
- [x] Add a backend-neutral KnowledgeBase facade, deterministic in-memory backend and legacy
  service adapter.
- [x] Add a backend-neutral LongTermMemory facade, per-user in-memory backend and legacy service
  adapter.
- [x] Make the existing Viking memory service implement the new backend SPI without changing its
  original ADK API.
- [x] Add an ADK-compatible ShortTermMemory facade, backend SPI, idempotent session creation,
  after-load callback, deterministic in-memory backend and existing-service adapter.
- [x] Add a provider-neutral short-term-memory history processor that rewrites loaded sessions
  without mutating persisted events, while appending new events to canonical stored history.
- [x] Add an optional `veadk-memory-sqlite` module with complete ADK session CRUD, event append,
  recent-event filtering, cross-instance persistence and stale-session overwrite protection.
- [x] Align save-session behavior with Python: first save, event/time thresholds, session-switch
  flush, configurable error handling, per-user async serialization and automatic
  `Agent.autoSaveSession(true)` callback wiring, while retaining the legacy zero-argument callback.
- [x] Add Ark LLM model fallback constructors, pre-output fallback retry, streaming no-switch
  safety after partial output, parallel streaming tool-call assembly, and finishReason/usage
  metadata propagation.
- [x] Map Ark LLM ADK generation config into Ark requests: temperature, topP, max output tokens,
  stop sequences, penalties, candidate/logprob settings and typed/raw structured JSON formats.
- [x] Map Ark LLM ADK multimodal input parts into Ark chat content parts for inline/file image
  and video inputs while preserving plain text request compatibility.
- [x] Map Ark LLM tool-call history into Ark assistant/tool messages and preserve returned tool-call
  ids in ADK function-call responses.

## In progress

- [ ] Complete Ark LLM fallback, response-cache and multimodal behavior parity.
- [ ] Add an automated binary API comparison profile in addition to reflection contracts.

## Latest validation

- [x] `./mvnw -o -B -ntp clean verify`

Result: 130 tests, zero failures/errors/skips; all four reactor modules, Spotless, JaCoCo, example
compilation, main JARs, sources JARs and Javadoc JARs passed with OpenJDK 17.0.17.

## Planned batches

1. Build and compatibility gates.
2. Typed configuration and environment compatibility.
3. Agent, Runner, processors and metadata.
4. Ark model and embedding parity.
5. Knowledge-base and memory SPIs plus deterministic backends.
6. External storage integrations.
7. Tools, MCP, sandbox and skills.
8. A2A, AgentKit application and evaluation.
9. Tracing, prompts and authentication.
10. A2UI, realtime, multimodal, tunnel, runtimes, CLI and cloud integrations.
11. Full regression, examples, documentation and release candidate.
