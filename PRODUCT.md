# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Graviton's local console serves developers, infrastructure engineers, and technical evaluators who need to operate and inspect a real content-addressed storage runtime from a browser.

## Product Purpose

Graviton is the byte substrate: it stores payloads up to the public 1 TiB logical limit as streamed, content-addressed blocks and immutable manifests, then returns cryptographically derived identities that higher-level systems can retain. Configured request, tenant, memory, and backend limits may be lower. The local console must let an operator upload, organize, inspect, verify, download, and remove logical file references while seeing actual deduplication and upload-locality outcomes from the running system.

## Positioning

The console is a direct operational surface over Graviton's real CAS and Shardcake services. It does not simulate uploads, invent storage metrics, maintain a browser-only shadow model, or present Graviton as a document-management system. Document-domain systems remain downstream consumers of Graviton content IDs and streams.

## Operating Context

The default local workflow runs one server with filesystem blocks and manifests. The optional distributed showcase uses PostgreSQL metadata, shared S3-compatible block storage, a Shardcake manager, and multiple Graviton upload nodes. Files may be large, uploads and downloads must remain streaming, and a browser session may navigate folders while transfers continue.

## Capabilities and Constraints

- Upload and download bytes through streaming HTTP paths without whole-file materialization.
- Expose real content IDs, logical size, block count, fresh blocks, duplicate blocks, and verified download actions.
- Keep console folders and filenames as mutable operator-catalog references to immutable CAS blobs, not as a document-domain model.
- Route session-scoped uploads through an enabled Shardcake topology in the local showcase deployment.
- Preserve CAS correctness when locality is unavailable: locality is an optimization, never a second storage format.
- Make deletion semantics explicit. Removing a catalog reference does not silently erase shared blocks.
- Support desktop and mobile browsers with keyboard-accessible controls and reduced-motion behavior.

## Brand Commitments

Keep the established Graviton name, orbital mark, green/cyan/violet/pink technical palette, and restrained matrix-rain motif. The console is an operational tool, so visual flair must never obscure storage state or primary actions.

## Evidence on Hand

- Real filesystem, PostgreSQL, S3-compatible, PDF-aware CAS, HTTP range-download, and verification implementations in this repository.
- Shardcake upload-locality services and multi-node reassignment tests in `modules/integration/graviton-shardcake`.
- Existing Graviton logo and documentation theme under `docs/public` and `docs/.vitepress/theme`.
- No customer testimonials, production throughput claims, or physical terabyte transfer proof should be fabricated.

## Product Principles

- Show actual system state and outcomes.
- Stream payloads end to end.
- Keep mutable organization orthogonal to immutable content identity.
- Make distributed ownership observable without making it the user's problem.
- Prefer a small number of obvious actions over explanatory interface copy.

## Accessibility & Inclusion

The console must be usable with keyboard navigation, visible focus, semantic controls, sufficient contrast, reduced motion, and responsive layouts down to mobile widths.
