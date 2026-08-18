# Backward compatibility policy

Parity work is additive. Existing Java users must be able to upgrade without source changes.

## Protected compatibility surface

The following are protected from incompatible changes:

- Maven coordinates and Java 17 bytecode baseline.
- Existing `com.volcengine.veadk` packages, public classes, interfaces, records, constructors,
  methods, fields, and generic return types.
- `Runner` constructors and its inheritance from Google ADK's runner.
- Existing environment-variable names and their current defaults.
- JSON property names and response shapes used by tools and Volcengine wrappers.
- Tool names, descriptions, argument names, and result keys.
- Existing examples and documented invocation patterns.

## Rules for implementation

1. Do not remove, rename, narrow, or relocate an existing public API.
2. Add overloads, builders, adapters, and new types instead of changing existing signatures.
3. Add only `default` methods to an existing public interface.
4. Keep old environment variables as aliases when introducing Python-compatible configuration.
5. Preserve old defaults unless an explicit opt-in selects new behavior.
6. Keep heavy or backend-specific dependencies out of the existing core artifact where possible.
7. Deprecations remain functional for the entire 0.x parity line; removal requires a future major
   compatibility decision.
8. Exceptions may gain more context, but existing exception categories and success result shapes
   must not change unexpectedly.

## Automated gates

The build will gain the following gates before broad feature work:

- `japicmp` or Revapi comparison against the captured 0.0.2 API.
- Characterization tests for existing public constructors, environment lookup, JSON shapes,
  tools, model streaming, memory, and knowledge-base behavior.
- Compilation of unchanged legacy examples.
- JDK 17 and 21 build matrix.
- Offline unit tests by default; external-service tests in an explicit integration profile.

An implementation batch is complete only when unit, compatibility, and relevant integration
tests pass together.
