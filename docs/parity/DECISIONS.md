# Parity architecture decisions

## D1: Port contracts, not Python syntax

Python source and tests define observable behavior. Java implementations use Java conventions,
Google ADK Java types, reactive APIs already exposed by the project, and stable service-provider
interfaces. A matching directory tree is not considered parity by itself.

## D2: Preserve the current core artifact

`com.volcengine.veadk:veadk-java` remains the compatibility artifact. Core contracts and
lightweight implementations stay there. Integrations that require large database, browser,
realtime, or cloud SDK dependency graphs should be optional Maven modules.

## D3: Introduce SPIs before backend implementations

Knowledge-base, long-term-memory, short-term-memory, evaluation, tracing, authentication, and
runtime functionality receive stable Java interfaces first. Existing Viking services are adapted
to those interfaces without removing their current APIs.

## D4: Separate deterministic and live tests

The normal build must not require cloud credentials or network access. WireMock, fake clients,
in-memory implementations, and Testcontainers cover deterministic behavior. Credentialed smoke
tests run only through an opt-in integration profile.

## D5: Treat Google ADK differences as an adapter boundary

Python currently depends on Google ADK `>=1.34.0`, while Java pins Google ADK `0.4.0`. New VeADK
APIs must not expose avoidable version-specific internals. Any ADK upgrade must first pass the
captured public API and behavior tests.

## D6: Deliver in reversible batches

Each feature batch includes its contracts, implementation, tests, documentation, and parity
matrix update. No batch may depend on an untested later batch to restore a green build.

## D7: Configuration precedence is explicit

Java resolves process environment over `.env`, then flattened `config.yaml`, matching the Python
observable behavior. Typed access is additive; existing `EnvUtil` methods keep their signatures
and defaults. Diagnostic output always redacts credentials.

## D8: VeADK Agent remains an ADK Agent

The Java `Agent` extends Google ADK's `LlmAgent`, so it works wherever existing ADK APIs expect a
`BaseAgent`. VeADK capabilities are builder options and metadata contracts rather than a parallel
runtime hierarchy.

## D9: Test instrumentation starts with the JVM

Mockito inline mocking uses the Byte Buddy Java agent supplied at Surefire JVM startup. This
avoids runtime self-attachment, which is unavailable in some containers and managed CI sandboxes.
