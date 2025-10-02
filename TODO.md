# TODO

- Translate binary streaming and chunking docs from Torrent into Graviton's documentation.
- Create a getting-started guide that introduces core concepts and basic usage.
- Update navigation links in `docs/src/main/mdoc/index.md` to reference new pages.
- Replace any remaining references to the Torrent library in existing docs.
- Document Prometheus metrics and structured logging in greater detail.
- Ensure `./sbt docs/mdoc test` passes after each documentation change.
- Translate Torrent API reference pages (binary store, chunking, content detection).
- Port performance and optimization guides from Torrent.
- Bring over core concept docs like content-addressable storage and binary streaming.
- Add a contributing guide tailored for Graviton.

- Outline a v0.1.0 roadmap covering APIs, packaging, and release targets.
- Add usage docs for the CLI and HTTP gateway.
- Provide configuration examples for filesystem and S3 backends.
- Flesh out metrics and logging examples with code snippets.
- Expand test coverage, including integration tests via TestContainers.
- Set up CI workflows and publishing to Maven Central.

## v0.1.0

- Finalize BlockStore and BlobStore APIs.
- Provide filesystem and S3 implementations with configuration docs.
- Ship CLI for ingesting and fetching files.
- Expose HTTP gateway for remote access.
- Publish basic metrics and structured logging.
- Deliver getting-started and backend configuration guides.
- Ensure integration tests cover core storage paths.
- Set up CI workflows and artifact publishing.

## Future

- Additional blob store backends.
- Advanced caching and deduplication strategies.
- High-level SDKs for common languages.
