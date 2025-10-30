# Design Documents

This section contains detailed design documents for major Graviton features and architectural decisions.

## Active Designs

### Core System

- **Scan Composition Model** — Event-driven stream processing (see [Scans & Events](../core/scans))
- **Range Algebra** — Byte range operations and tracking (see [Ranges & Boundaries](../core/ranges))
- **Content-Defined Chunking** — FastCDC and anchored chunking strategies (see [Chunking Strategies](../ingest/chunking))
- **Replication Model** — Replica placement and repair (see [Replication & Repair](../runtime/replication))

### Schema & Types

- **Schema Evolution** — Forward-compatible manifest format (planned)
- **Binary Attributes** — Advertised vs confirmed metadata (see [Schema & Types](../core/schema))
- **Hashing Strategy** — Multi-algorithm support and key derivation (planned)

### Storage & Performance

- **Hybrid Backend Architecture** — PostgreSQL + S3 design (see [Backends](../runtime/backends))
- **Tiered Storage** — Hot/warm/cold data management (planned)
- **Deduplication Index** — Block-level dedup tracking (planned)
- **Compression Strategy** — When and how to compress (planned)

### Operations

- **Metrics Architecture** — Prometheus integration and key metrics (see [Constraints & Metrics](../constraints-and-metrics))
- **Rate Limiting** — Token bucket implementation (planned)
- **Authentication Model** — JWT, API keys, and signed URLs (planned)
- **Multi-tenancy** — Isolation and quota management (planned)

## Future Designs

### Phase 2 (Post v0.1.0)

- **Anchored Ingest Pipeline** — Format-aware chunking with DFA tokenization
- **Self-Describing Frame Format** — Versioned frame encoding
- **CDC Base Blocks** — Dedup via rolling hash index
- **Format-Aware Views** — PDF/ZIP structural access

### Phase 3 (Future)

- **Erasure Coding** — Alternative to replication
- **Geo-Replication** — Cross-region strategies
- **Query Engine** — Content search and indexing
- **Streaming Analytics** — Real-time metrics on ingest

## Design Template

Use this template for new designs:

```markdown
# Feature Name

## Status

**Status:** Proposed | Draft | Accepted | Implemented  
**Author:** @username  
**Created:** 2025-10-30  
**Updated:** 2025-10-30

## Summary

One-paragraph overview of the feature.

## Motivation

Why do we need this? What problem does it solve?

## Design

### Architecture

Describe the high-level design.

### API

Show interfaces and examples.

### Data Model

Describe any new types or schemas.

### Implementation

Key implementation details.

## Alternatives Considered

What other approaches were considered and why were they rejected?

## Testing Plan

How will this be tested?

## Performance Impact

Expected performance characteristics.

## Migration Path

How do we migrate from the current state?

## Open Questions

What's still TBD?

## References

Links to related designs, issues, or documentation.
```

## Review Process

1. **Propose**: Create draft in `/docs/design/`
2. **Discussion**: Share in GitHub Discussions or PR
3. **Iterate**: Address feedback
4. **Accept**: Mark as "Accepted" when consensus reached
5. **Implement**: Reference design in implementation PRs
6. **Update**: Keep design docs in sync with implementation

## Design Principles

### Modularity

- Pure domain logic separate from effects
- Backends implement abstract ports
- Protocols independent of core logic

### Type Safety

- Use opaque types for domain primitives
- Leverage zio-schema for serialization
- Prefer compile-time over runtime checks

### Performance

- Streaming by default
- Lazy evaluation where appropriate
- Minimize allocations in hot paths

### Observability

- Structured logging with correlation IDs
- Prometheus metrics for all operations
- Tracing for distributed operations

### Testability

- Pure functions where possible
- Dependency injection via ZLayer
- Property-based testing for algorithms

## See Also

- **[Architecture](../architecture)** — System overview
- **[Contributing](../dev/contributing)** — Development process
- **[API Reference](../api)** — Current API surface

::: tip
When in doubt, write a design doc before implementing a major feature!
:::
