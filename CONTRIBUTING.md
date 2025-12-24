# Contributing to Graviton

Thank you for helping improve Graviton! This guide outlines the workflow, coding standards, and validation steps contributors should follow when making changes.

## Table of contents
- [Getting started](#getting-started)
- [Workflow](#workflow)
- [Coding standards](#coding-standards)
- [Testing](#testing)
- [Documentation](#documentation)
- [Database schema changes](#database-schema-changes)
- [Commits and pull requests](#commits-and-pull-requests)

## Getting started
1. Install the following prerequisites:
   - Java 21+
   - Node.js 20+ (for documentation and Scala.js frontend work)
   - sbt (we vendor `./sbt` for consistent builds)
2. Clone the repository and initialize submodules:

   ```bash
   git clone https://github.com/your-org/graviton.git
   cd graviton
   git submodule update --init --recursive
   ```

## Workflow
- Keep your branch synced with the latest `main` to minimize rebase friction:

  ```bash
  git fetch origin main
  git rebase origin/main
  ```

- Prefer small, focused commits that pair code with any accompanying documentation or schema updates.
- Include reproductions and assumptions in commit messages when fixing bugs or adding features.

## Coding standards
- Follow the existing module boundaries—shared domain types live in `modules/graviton-core`, streaming utilities in `modules/graviton-streams`, runtime ports in `modules/graviton-runtime`, and server wiring in `modules/server`.
- Use idiomatic Scala 3 and avoid introducing new dependencies without discussing the impact on downstream modules.
- Keep error handling explicit; avoid blanket catch-all patterns and prefer domain-specific errors.
- Run the code formatter before committing to enforce project-wide style:

  ```bash
  TESTCONTAINERS=0 ./sbt scalafmtAll
  ```

## Testing
- Always run the full suite before opening a pull request:

  ```bash
  TESTCONTAINERS=0 ./sbt scalafmtAll test
  ```

- Set `TESTCONTAINERS=0` to skip external containerized integrations during quick iteration.
- When working on streaming or backend modules, add targeted unit tests alongside new behaviors. Integration tests should live next to their modules (for example, `modules/graviton-runtime/src/test/scala`).

## Documentation
- Update relevant docs when changing public APIs or operational flows. The documentation site is under `docs/` and uses VitePress with an embedded Scala.js demo.
- To preview docs locally:

  ```bash
  sbt buildFrontend
  cd docs
  npm install
  npm run docs:dev
  ```

- Keep navigation links and code samples in sync with the Scala implementation:
  - Typecheck markdown snippets via `./sbt docs/mdoc checkDocSnippets`.
  - When editing snippet sources under `docs/snippets/`, run `./sbt syncDocSnippets` to refresh the rendered blocks before committing.

## Database schema changes
Schema updates must be reflected in generated bindings so migrations and code stay in lockstep:

1. Start a local PostgreSQL instance without Docker (for example: `apt-get install postgresql && sudo pg_ctlcluster 18 main start`).
2. Apply the DDL to an empty database:

   ```bash
   psql -d graviton -f modules/pg/ddl.sql
   ```

3. Regenerate bindings and commit them together with the DDL change:

   ```bash
   PG_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/graviton \
   PG_USERNAME=postgres \
   PG_PASSWORD=postgres \
   ./sbt "dbcodegen/run"
   ```

## Commits and pull requests
- Include a concise summary of the user-visible impact in your commit messages.
- Ensure CI can reproduce your changes: avoid environment-specific assumptions and keep test fixtures deterministic.
- Reference relevant documentation or design notes when introducing new behavior (for example, `docs/architecture.md`).
- When opening a pull request, describe the change, the tests you ran, and any follow-up work. Link to new docs or diagrams that clarify your approach.
