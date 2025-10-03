# Manifests and Frames

Graviton represents logical blobs using manifests that list ordered block references alongside range metadata and attributes.

## Manifest structure

- **Header**: blob size, hashing algorithm, creation timestamp.
- **Entries**: ordered list of block descriptors (key bits, spans, optional encryption info).
- **Attributes**: advertised and confirmed metadata captured at ingest.

## Framed encoding

The framed manifest format is a binary envelope that wraps the logical manifest. It starts with a magic prefix, followed by a schema identifier, and length-prefixed sections. The encoder and decoder live in `graviton-core` so they can be used by any runtime without introducing effects.

## Forward compatibility

New fields are added via optional sections keyed by numeric tags. Older readers skip unknown tags, while newer readers can surface richer metadata such as compression hints or redaction fingerprints.
