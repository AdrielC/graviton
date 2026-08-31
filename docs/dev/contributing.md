# Contributing

The repository's [canonical contribution guide](https://github.com/AdrielC/graviton/blob/main/CONTRIBUTING.md) defines setup, module boundaries, validation, schema changes, pull requests, and releases. This page records the documentation-specific rules that CI enforces.

## Keep the byte path honest

Production uploads and downloads remain `ZStream` values. Whole-body HTTP decoders, unbounded `runCollect`, `Files.readAllBytes`, and hidden byte-array accumulation are rejected by `scripts/check-byte-streaming-hygiene.sh`. A permitted materialization needs a named refined maximum, a runtime `max + 1` overflow check, and a reason that the value belongs on the control plane.

Storage ports use `StoreError` for expected failures. Backend exceptions can remain diagnostic causes, but public callers should not need to inspect an arbitrary `Throwable` to decide whether a failure is missing, conflicting, invalid, unavailable, or retryable.

## Keep claims attached to proof

Use these status terms consistently:

- released in a named version;
- implemented on `main` but not yet released;
- optional and configuration-gated;
- target qualification required;
- not shipped.

The [implementation status ledger](../implementation-status.md) provides the human-readable boundary. `docs/status/implementation-evidence.json` maps major claims to source and test symbols, and CI runs:

```bash
python3 scripts/check-doc-truth.py
```

The checker rejects missing evidence, nonexistent script commands, invented metric keys, and known stale assertions. It complements executable examples and VitePress link checking; it does not turn prose into a proof by itself.

## Validate documentation

```bash
npm ci --prefix docs
./sbt contentLab/test pdfContentLab/test buildDocsAssets
./sbt docs/mdoc checkDocSnippets
npm run docs:build --prefix docs
```

Edit managed examples under `docs/snippets`, run `./sbt syncDocSnippets`, and commit the regenerated Markdown block. Do not hand-edit the generated portion.

When documenting operational behavior, link to the concrete service, adapter, test, script, or machine-readable contract. A unit test is not a provider service-level claim, a logical-size test is not a physical transfer, Terraform validation is not a deployed AWS cell, and a browser playground is not durable storage.

## Current repository boundary

The active SBT graph is defined in `build.sbt`. Files merely present below `modules/` are not automatically shipped. The Tika design page, unbuilt source trees, and future compression, encryption, extraction, and search work must stay explicitly outside the implemented module list.
