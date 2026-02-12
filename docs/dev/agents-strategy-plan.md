# Agent Strategy & Execution Plan

## Goal
Define an actionable strategy for consistent code style, streaming patterns, and domain modeling. Provide a shared plan that aligns with ZIO Streams guidance, Iron refined types, and existing Graviton module boundaries.

## Inputs & References
- ZIO Streams official documentation (stream composition, pipelines, sinks, chunk management): https://zio.dev/reference/stream/
- Iron refined types documentation (refined domain modeling, smart constructors): https://www.ironrefined.org/
- Graviton contribution workflow and module boundaries from `CONTRIBUTING.md`.

## Codebase Snapshot (What’s Working Well)
- Clear module segmentation: domain types and shared abstractions live under `modules/graviton-core`, streaming utilities in `modules/graviton-streams`, runtime ports in `modules/graviton-runtime`, and HTTP wiring in `modules/server`.
- Documentation is already structured for architecture + operational guidance under `docs/`, which supports adding shared standards without disrupting core API docs.
- Existing contributor workflow aligns with the “run formatting + tests” discipline and can be reinforced by the agent standards.

## Gaps & Opportunities
- Standards are present in `CONTRIBUTING.md`, but there is no dedicated, agent-focused checklist for streaming and refined type usage.
- Streaming patterns and domain modeling rules can be made more discoverable to reduce drift as the codebase grows.
- A shared execution checklist can turn the strategy into a repeatable process.

## Strategy Plan
1. **Add an agent standards hub**
   - Create `agents/AGENTS.md` to host the shared practices and references.
   - Include ZIO Streams and Iron refined type guidance with links to official documentation.
2. **Codify streaming + domain modeling patterns**
   - Outline preferred `ZStream`/`ZPipeline`/`ZSink` usage.
   - Emphasize refined domain types for sizes, indices, and identifiers.
3. **Align with existing workflow**
   - Reuse validation steps from `CONTRIBUTING.md`.
   - Provide a compact review checklist for each change.
4. **Track execution**
   - Keep a lightweight execution log to mark each planned step as complete.

## Execution Log (Planned)
- [x] Create `agents/AGENTS.md` with strategy anchors and coding practices.
- [x] Document ZIO Streams patterns aligned with the official docs.
- [x] Document Iron refined type patterns for domain modeling.
- [x] Add validation checklist and update existing workflow references.
- [x] Review module boundaries and confirm plan fits `modules/*` structure.

## Deep-Dive Playbook
For an exhaustive, step-by-step execution guide ("sicko mode"), see:
- `docs/dev/agent-strategy-playbook.md`
