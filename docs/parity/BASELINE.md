# VeADK parity baseline

Baseline captured on 2026-08-14 before parity work starts.

## Source revisions

| Repository | Revision | Role |
| --- | --- | --- |
| `volcengine/veadk-java` | `dc77c38dd2ab35d43535af13543f7c2b9b0a7189` | Compatibility baseline and implementation target |
| `volcengine/veadk-python` | `36f55ee24a18057300f564e20a63fba686badb47` | Read-only behavior and feature reference |

The Python revision is intentionally pinned. New upstream Python commits are not part of the
current parity target until this baseline is completed.

## Java baseline

- Maven coordinates: `com.volcengine.veadk:veadk-java:0.0.2`.
- Minimum Java release: 17.
- Google ADK dependency: 0.4.0.
- Main source files: 27.
- Test source files: 12.
- Example source/resource files: 4.
- Public packages already exposed under `com.volcengine.veadk` must remain available.

At the pinned revision the README showed version `0.0.1`. The current README corrects that
historical documentation mismatch to the Maven project version, `0.0.2`, without changing the
compatibility baseline.

## Python reference size

- Package files under `veadk/`: 492.
- Test files under `tests/`: 154.
- Google ADK requirement: `>=1.34.0`.

The large ADK version difference means behavior must be ported through explicit Java adapters.
Upgrading Google ADK Java is a separate compatibility decision, not an automatic prerequisite.

## Baseline test status

The user ran the unchanged baseline with `./mvnw -B -ntp clean verify` after configuring the
already-installed OpenJDK 17. All three reactor modules passed with 73 tests and no failures.

After the first parity batch, the same command also passes inside the restricted environment:
all three modules build, 88 tests pass, examples compile, and binary, source, and Javadoc jars are
created. Mockito now loads Byte Buddy at test-JVM startup so the test suite does not depend on
runtime agent attachment.
