# Use Cases

Graviton provides a modular content‑addressable storage layer that powers a
variety of binary workflows.  The following scenarios illustrate how the
system can be applied in practice.

## 1. Document Ingestion Pipelines

- Upload PDFs, TIFFs, or scanned forms via the CLI or HTTP gateway.
- Stream bytes directly into the filesystem or S3 stores without temporary
  files.
- Attach metadata such as court, case ID, or classification alongside the
  stored file.
- Trigger downstream processing like OCR or page splitting once the file is
  persisted.

## 2. Reactive File Serving

- Serve large files over HTTP/1.1 or HTTP/2 using `ZStream`.
- Integrate with HTTP frameworks such as Tapir or Quasar's gateway.
- Enforce back‑pressure from socket to disk for efficient resource usage.
- Support byte‑range requests and partial streaming.

## 3. Scanning and Browser Uploads

- Use TWAIN‑connected scanners to stream documents directly into Graviton.
- Push file content over WebSockets or gRPC while reporting progress in real
  time.
- Avoid temporary files by streaming into the storage layer as scans occur.

## 4. Streaming Inference

- Deliver binary data to in‑browser WASM models or small server‑side LLMs as it
  streams from Graviton.
- Perform tasks such as PDF classification, document type prediction, or
  signature detection without staging full files on disk.

## 5. Secure Court Submissions

- Store encrypted filings with short‑lived download URLs.
- Enforce time‑based expiration and deletion policies.
- Transfer over ALPN‑encrypted HTTP/2 channels with scoped access control.

## 6. Multi‑Tier Storage Abstraction

- Abstract over hot and cold storage like local disk, S3, and Glacier.
- Cache frequently accessed files locally via the filesystem store while keeping
  canonical copies in S3.
- Minimize latency and avoid repeated downloads for commonly requested data.

## 7. Metadata‑Driven Routing

- Choose backends dynamically based on document type, size, or content hash.
- Enforce constraints such as maximum size, required metadata, or retention
  policy at ingestion time.

These use cases demonstrate how Graviton's streaming model and pluggable
backends support both high‑throughput and low‑latency binary workflows.

