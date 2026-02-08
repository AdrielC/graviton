# Graviton Agent Standards

## Purpose
This file defines the expectations for contributors (human or automated) working in the Graviton codebase. It captures the shared strategy, coding standards, and validation steps so changes remain consistent with project goals.

## Scope
- Applies to all work in this repository.
- When a more specific `AGENTS.md` exists deeper in the tree, it overrides these rules for its scope.

## Strategy Anchors
- Preserve module boundaries: shared domain types in `modules/graviton-core`, streaming utilities in `modules/graviton-streams`, runtime ports in `modules/graviton-runtime`, and server wiring in `modules/server`.
- Prefer small, well-scoped commits that bundle code, tests, and docs updates together.
- Keep changes aligned with the docs architecture and API narratives under `docs/`.

## ZIO Streaming Guidance
Use ZIO Streams patterns aligned with the official guidance:
- Favor `ZStream` + `ZPipeline` compositions for declarative dataflow, keeping effectful boundaries explicit.
- Use `ZSink` for controlled aggregation (e.g., hashing, metrics), and keep sink logic side-effect free unless explicitly required.
- Use `Chunk`-aware operations to minimize allocations and avoid `runCollect` for large streams.
- Prefer `ZPipeline` for transformations that can be reused across ingest paths (e.g., chunking, attribute capture, validation).
- Document backpressure expectations and resource lifecycles (e.g., `ZStream.fromInputStreamScoped`).

Reference: https://zio.dev/reference/stream/

## Iron Refined Types Guidance
Use Iron (or refined types) to model domain invariants directly in types:
- Create focused domain types for sizes, indices, identifiers, and bounds instead of raw primitives.
- Keep validation close to construction (smart constructors or `refine` helpers) and document failure modes.
- Prefer newtypes/opaque wrappers over type aliases to prevent accidental misuse.
- Centralize reusable constraints (e.g., `NonEmpty`, `Positive`, `MaxSize`) in shared domain modules.

Reference: https://www.ironrefined.org/

## Code Style & Patterns
- Scala 3 idioms only; avoid introducing new dependencies without discussing impact.
- Keep error handling explicit with domain-specific errors.
- Prefer pure transformations and `ZIO` effects over side-effecting helpers.
- Use consistent naming: `*Store` for storage abstractions, `*Service` for runtime ports, `*Routes` for HTTP wiring.

## Documentation & Validation
- Update docs for public API or operational flow changes.
- Validate formatting and tests before committing:
  - `TESTCONTAINERS=0 ./sbt scalafmtAll test`
  - `./sbt docs/mdoc checkDocSnippets`

## Review Checklist
- [ ] Module boundaries respected.
- [ ] Streaming changes use `ZStream`/`ZPipeline`/`ZSink` idioms.
- [ ] Domain invariants encoded with refined types.
- [ ] Docs and tests updated where needed.
- [ ] Validation commands executed.

## Execution Playbook
For the full, step-by-step execution guide, see `docs/dev/agent-strategy-playbook.md`.
