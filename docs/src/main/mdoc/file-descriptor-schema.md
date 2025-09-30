# Manifest Schema

The `Manifest` and related key types (`BlockKey`, `BlobKey`) are defined using
`zio.schema.Schema`. These definitions act as the single source of truth and
can be rendered to JSON Schema when needed.

## Migration Notes

Schema v1 introduces explicit validation for `filename` and content type
attributes via `BinaryAttributes.validate`. Clients must ensure these
attributes are well-formed or `put` operations will be rejected.
