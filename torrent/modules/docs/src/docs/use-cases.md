Use Cases
---------------------


`torrent` is designed for high-performance binary file transfer with ZIO semantics — but its modularity allows it to go beyond “store/retrieve.” Below are the primary and emerging use cases at Tybera.

### 1. **Document Ingestion Pipelines**
- Upload documents (PDFs, TIFFs, scanned forms) via HTTP/2 or gRPC.
- Stream directly into Postgres or S3 without intermediate files.
- Associate with rich metadata (court, case ID, classification).
- Trigger downstream processing (e.g., OCR, page splitting, validation).

### 2. **Reactive File Serving**
- Serve large files over HTTP/2 or HTTP/1.1 using `ZStream`.
- Integrate with Quartz or Tapir streaming endpoints.
- Implement strict backpressure from socket to disk.
- Support byte-range requests and partial streaming.

### 3. **Scanning + Browser Upload (FLEX Integration)**
- Use local TWAIN-connected scanners to stream binary files into the web app.
- Push file content via WebSocket directly into `torrent` via gRPC or HTTP backend.
- Track scan progress and stream metadata in parallel.
- Provide users with immediate UI feedback without temp files.

### 4. **Streaming Inference (WASM + In-Browser Models)**
- Load small LLMs or classifiers (e.g., Q8-formatted models) in the browser via WASM.
- As binary data is streamed from `torrent`, pipe chunks into a WASM-compiled inference engine.
- Use this for:
  - PDF classification
  - Document-type prediction
  - Signature field detection
  - Confidentiality filtering
- Enable hybrid deployment:
  - Run model in-browser via WASM (offline or low-latency mode).
  - Fallback to server-side processing if browser cannot support inference.

### 5. **Secure Court Submissions**
- Store court filings in encrypted binary form.
- Support controlled, one-time download URLs via `torrent`.
- Time-based expiration and deletion.
- Transfer over ALPN-encrypted HTTP/2 channels with scoped access control.

### 6. **Multi-tier Storage Abstraction**
- Abstract over cold/hot storage (e.g., local disk + S3 + Glacier).
- Stream files from S3, cache locally via `torrent-fs`, and serve via Quartz.
- Minimize latency and avoid repeated downloads.

### 7. **Metadata-Driven Rules**
- Route file streams based on document type, size, org ID, or content hash.
- Dynamically choose backend (e.g., small → DB, large → S3).
- Enforce constraints like max size, required metadata, retention policy.