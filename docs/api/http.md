# HTTP API

Graviton provides RESTful HTTP endpoints for blob operations, built on zio-http.

## Endpoints

### Upload Blob

```http
POST /api/v1/blobs
Content-Type: application/octet-stream
X-Content-Length: <size>
X-Content-Hash: <sha256-hex>
X-Attributes: {"key":"value"}

<binary data>
```

**Response:**
```json
{
  "key": "0123456789abcdef...",
  "size": 1048576,
  "hash": "sha256:abcd1234...",
  "attributes": {
    "content-type": "application/pdf"
  }
}
```

### Get Blob

```http
GET /api/v1/blobs/:key
```

**Response:**
```http
HTTP/1.1 200 OK
Content-Type: application/octet-stream
Content-Length: 1048576
ETag: "abcd1234..."

<binary data>
```

**Partial retrieval:**
```http
GET /api/v1/blobs/:key
Range: bytes=0-1023
```

**Response:**
```http
HTTP/1.1 206 Partial Content
Content-Range: bytes 0-1023/1048576
Content-Length: 1024

<binary data>
```

### Blob Metadata

```http
HEAD /api/v1/blobs/:key
```

**Response:**
```http
HTTP/1.1 200 OK
Content-Length: 1048576
Content-Type: application/pdf
ETag: "abcd1234..."
X-Created-At: 2025-10-30T12:00:00Z
X-Attributes: {"key":"value"}
```

### List Blobs

```http
GET /api/v1/blobs?prefix=docs/&limit=100&offset=0
```

**Response:**
```json
{
  "blobs": [
    {
      "key": "0123456789abcdef...",
      "size": 1048576,
      "content_type": "application/pdf",
      "created_at": "2025-10-30T12:00:00Z"
    }
  ],
  "next_offset": 100,
  "has_more": true
}
```

### Delete Blob

```http
DELETE /api/v1/blobs/:key
```

**Response:**
```json
{
  "deleted": true,
  "key": "0123456789abcdef..."
}
```

## Multipart Upload

### Start Upload

```http
POST /api/v1/uploads
Content-Type: application/json

{
  "fileName": "movie.mp4",
  "totalSize": 104857600,
  "mediaType": "video/mp4",
  "metadata": {
    "ingest": {
      "operator": "cli",
      "source": "demo"
    }
  },
  "preferredChunkSize": 5242880,
  "partChecksums": {
    "1": "sha256:abcd..."
  },
  "wholeFileChecksum": "sha256:ffff..."
}
```

**Response:**
```json
{
  "uploadId": "upload-abc123",
  "chunkSize": 5242880,
  "maxChunks": 256,
  "expiresAtEpochSeconds": 1730738400
}
```

### Upload Part

```http
PUT /api/v1/uploads/:upload_id/parts/:part_number
Content-Type: application/octet-stream
Content-Length: 5242880

<binary data>
```

**Response:**
```json
{
  "partNumber": 1,
  "acknowledgedSequence": 42,
  "receivedBytes": 22020096
}
```

### Complete Upload

```http
POST /api/v1/uploads/:upload_id/complete
Content-Type: application/json

{
  "parts": [
    {"partNumber": 1, "offset": 0, "size": 5242880, "checksum": "sha256:abcd..."},
    {"partNumber": 2, "offset": 5242880, "size": 5242880, "checksum": "sha256:efgh..."}
  ],
  "expectedChecksum": "sha256:beef..."
}
```

**Response:**
```json
{
  "documentId": "doc-0123456789abcdef",
  "attributes": {
    "content-type": "video/mp4"
  }
}
```

### Abort Upload

```http
DELETE /api/v1/uploads/:upload_id
```

**Response:**
```json
{
  "aborted": true,
  "upload_id": "upload-abc123"
}
```

## Admin Endpoints

### Health Check

```http
GET /health
```

**Response:**
```json
{
  "status": "healthy",
  "version": "0.1.0",
  "uptime_seconds": 3600
}
```

### Metrics

```http
GET /metrics
```

**Response:** Prometheus text format
```
# HELP graviton_blobs_total Total number of blobs
# TYPE graviton_blobs_total counter
graviton_blobs_total 12345

# HELP graviton_storage_bytes Total storage bytes
# TYPE graviton_storage_bytes gauge
graviton_storage_bytes 1234567890
```

### Replica Status

```http
GET /api/v1/admin/replicas/:key
```

**Response:**
```json
{
  "key": "0123456789abcdef...",
  "replicas": [
    {
      "sector": "sector-1",
      "healthy": true,
      "last_verified": "2025-10-30T12:00:00Z"
    },
    {
      "sector": "sector-2",
      "healthy": true,
      "last_verified": "2025-10-30T11:30:00Z"
    }
  ],
  "replication_factor": 3,
  "status": "healthy"
}
```

## Error Responses

All errors follow this format:

```json
{
  "error": {
    "code": "BLOB_NOT_FOUND",
    "message": "Blob with key 0123... does not exist",
    "details": {
      "key": "0123456789abcdef..."
    }
  }
}
```

### Error Codes

| HTTP Code | Error Code | Description |
|-----------|------------|-------------|
| 400 | `INVALID_REQUEST` | Malformed request |
| 401 | `UNAUTHORIZED` | Missing or invalid auth |
| 403 | `FORBIDDEN` | Insufficient permissions |
| 404 | `BLOB_NOT_FOUND` | Blob doesn't exist |
| 409 | `CONFLICT` | Key already exists |
| 413 | `BLOB_TOO_LARGE` | Exceeds size limit |
| 429 | `RATE_LIMIT_EXCEEDED` | Too many requests |
| 500 | `INTERNAL_ERROR` | Server error |
| 503 | `SERVICE_UNAVAILABLE` | Temporary unavailability |

## Client Examples

### cURL

**Upload:**
```bash
curl -X POST http://localhost:8080/api/v1/blobs \
  -H "Content-Type: application/octet-stream" \
  -H "X-Content-Hash: sha256:$(sha256sum file.bin | cut -d' ' -f1)" \
  --data-binary @file.bin
```

**Download:**
```bash
curl http://localhost:8080/api/v1/blobs/0123456789abcdef... \
  -o downloaded.bin
```

**Partial download:**
```bash
curl http://localhost:8080/api/v1/blobs/0123456789abcdef... \
  -H "Range: bytes=0-1023" \
  -o first-1k.bin
```

### Python (requests)

```python
import requests
import hashlib

# Upload
def upload_blob(url: str, data: bytes) -> dict:
    hash_hex = hashlib.sha256(data).hexdigest()
    
    response = requests.post(
        f"{url}/api/v1/blobs",
        headers={
            "Content-Type": "application/octet-stream",
            "X-Content-Hash": f"sha256:{hash_hex}"
        },
        data=data
    )
    response.raise_for_status()
    return response.json()

# Download
def download_blob(url: str, key: str) -> bytes:
    response = requests.get(f"{url}/api/v1/blobs/{key}")
    response.raise_for_status()
    return response.content

# Multipart upload
def multipart_upload(url: str, file_path: str, chunk_size: int = 5*1024*1024):
    # Start
    with open(file_path, 'rb') as f:
        f.seek(0, 2)
        total_size = f.tell()
        f.seek(0)
        
        start_resp = requests.post(
            f"{url}/api/v1/uploads",
            json={"total_size": total_size}
        )
        start_resp.raise_for_status()
        upload_id = start_resp.json()["upload_id"]
        
        # Upload parts
        parts = []
        part_num = 1
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            
            part_resp = requests.put(
                f"{url}/api/v1/uploads/{upload_id}/parts/{part_num}",
                headers={"Content-Type": "application/octet-stream"},
                data=chunk
            )
            part_resp.raise_for_status()
            parts.append(part_resp.json())
            part_num += 1
        
        # Complete
        complete_resp = requests.post(
            f"{url}/api/v1/uploads/{upload_id}/complete",
            json={"parts": parts}
        )
        complete_resp.raise_for_status()
        return complete_resp.json()
```

### JavaScript (fetch)

```javascript
// Upload
async function uploadBlob(url, data) {
  const hash = await crypto.subtle.digest('SHA-256', data);
  const hashHex = Array.from(new Uint8Array(hash))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
  
  const response = await fetch(`${url}/api/v1/blobs`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/octet-stream',
      'X-Content-Hash': `sha256:${hashHex}`
    },
    body: data
  });
  
  if (!response.ok) {
    throw new Error(`Upload failed: ${response.statusText}`);
  }
  
  return await response.json();
}

// Download
async function downloadBlob(url, key) {
  const response = await fetch(`${url}/api/v1/blobs/${key}`);
  
  if (!response.ok) {
    throw new Error(`Download failed: ${response.statusText}`);
  }
  
  return await response.arrayBuffer();
}

// Streaming upload
async function streamingUpload(url, file) {
  const formData = new FormData();
  formData.append('file', file);
  
  const response = await fetch(`${url}/api/v1/blobs`, {
    method: 'POST',
    body: formData
  });
  
  if (!response.ok) {
    throw new Error(`Upload failed: ${response.statusText}`);
  }
  
  return await response.json();
}
```

## Authentication

### Bearer Token

```http
GET /api/v1/blobs/:key
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

### API Key

```http
GET /api/v1/blobs/:key
X-API-Key: your-api-key-here
```

### Signed URLs (Future)

```http
GET /api/v1/blobs/:key?signature=...&expires=...
```

## Rate Limiting

Rate limits are enforced per client IP or API key:

**Headers:**
```http
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 999
X-RateLimit-Reset: 1635724800
```

**Rate limit exceeded:**
```http
HTTP/1.1 429 Too Many Requests
Retry-After: 60

{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Rate limit exceeded. Try again in 60 seconds."
  }
}
```

## CORS

CORS is configurable:

```hocon
graviton {
  http {
    cors {
      allowed-origins = ["https://app.example.com"]
      allowed-methods = ["GET", "POST", "PUT", "DELETE"]
      allowed-headers = ["Content-Type", "Authorization"]
      max-age = 3600
    }
  }
}
```

## See Also

- **[gRPC API](./grpc)** — High-performance binary protocol
- **[Getting Started](../guide/getting-started)** — Quick start guide
- **[Authentication](../ops/deployment#authentication)** — Security setup

::: tip
Use multipart upload for files > 100MB for better reliability and progress tracking!
:::
