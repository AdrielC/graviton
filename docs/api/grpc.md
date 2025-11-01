# gRPC API

Graviton exposes gRPC services for high-performance binary uploads and blob retrieval.

## Services

### BlobService

Basic blob operations:

```protobuf
service BlobService {
  // Get a blob by key
  rpc GetBlob(GetBlobRequest) returns (stream BlobChunk);
  
  // Get blob metadata without content
  rpc StatBlob(StatBlobRequest) returns (BlobMetadata);
  
  // Delete a blob
  rpc DeleteBlob(DeleteBlobRequest) returns (DeleteBlobResponse);
  
  // List blobs
  rpc ListBlobs(ListBlobsRequest) returns (stream BlobMetadata);
}

message GetBlobRequest {
  bytes key = 1;
  optional ByteRange range = 2;
}

message BlobChunk {
  bytes data = 1;
  int64 offset = 2;
}

message StatBlobRequest {
  bytes key = 1;
}

message BlobMetadata {
  bytes key = 1;
  int64 size = 2;
  string content_type = 3;
  map<string, string> attributes = 4;
  google.protobuf.Timestamp created_at = 5;
}
```

### UploadService

Bidirectional streaming upload:

```protobuf
service UploadService {
  // Bidirectional streaming upload
  rpc Upload(stream UploadMessage) returns (stream UploadAck);
}

message UploadMessage {
  oneof message {
    StartUpload start = 1;
    UploadChunk chunk = 2;
    CompleteUpload complete = 3;
    UploadAck ack = 4;
  }
}

message StartUpload {
  int64 total_size = 1;
  map<string, string> attributes = 2;
}

message UploadChunk {
  bytes data = 1;
  int64 offset = 2;
}

message CompleteUpload {
  bytes expected_hash = 1;
}

message UploadAck {
  string upload_id = 1;
  uint64 received_bytes = 2;
}
```

## Client Usage

### Scala Client

```scala
import graviton.protocol.grpc.client.UploadNodeGrpcClient
import graviton.protocol.grpc.client.UploadNodeGrpcClient.*
import zio.*
import zio.stream.ZStream

val uploadProgram = ZIO.serviceWithZIO[UploadServiceClient] { service =>
  val client = UploadNodeGrpcClient.fromService(service)
  val payload = ZStream.fromFileName("/tmp/archive.tar")

  client.uploadStream(
    data = payload,
    expectedSize = Some(128L * 1024 * 1024),
    attributes = Map("content-type" -> "application/x-tar")
  )
}

val runtime = Runtime.default
// Provide the generated UploadServiceClient layer from zio-grpc when running your application.
// runtime.unsafeRun(uploadProgram.provideLayer(uploadServiceClientLayer))
```

### Python Client

```python
import grpc
from graviton.proto import blob_pb2, blob_pb2_grpc

# Create channel
channel = grpc.insecure_channel('localhost:50051')
stub = blob_pb2_grpc.BlobServiceStub(channel)

# Get a blob
def get_blob(key: bytes) -> bytes:
    request = blob_pb2.GetBlobRequest(key=key)
    chunks = stub.GetBlob(request)
    return b''.join(chunk.data for chunk in chunks)

# Upload a blob
def upload_blob(data: bytes) -> bytes:
    upload_stub = blob_pb2_grpc.UploadServiceStub(channel)
    
    def request_iterator():
        # Start
        yield blob_pb2.UploadMessage(
            start=blob_pb2.StartUpload(total_size=len(data))
        )
        
        # Chunks (1MB each)
        chunk_size = 1024 * 1024
        for i in range(0, len(data), chunk_size):
            chunk = data[i:i + chunk_size]
            yield blob_pb2.UploadMessage(
                chunk=blob_pb2.UploadChunk(data=chunk, offset=i)
            )
        
        # Complete
        yield blob_pb2.UploadMessage(
            complete=blob_pb2.CompleteUpload()
        )
    
    # Send and receive
    responses = upload_stub.Upload(request_iterator())
    
    for response in responses:
        if response.HasField('complete'):
            return response.complete.key
```

### Go Client

```go
package main

import (
    "context"
    "io"
    
    pb "graviton/proto"
    "google.golang.org/grpc"
)

func GetBlob(ctx context.Context, client pb.BlobServiceClient, key []byte) ([]byte, error) {
    stream, err := client.GetBlob(ctx, &pb.GetBlobRequest{Key: key})
    if err != nil {
        return nil, err
    }
    
    var data []byte
    for {
        chunk, err := stream.Recv()
        if err == io.EOF {
            break
        }
        if err != nil {
            return nil, err
        }
        data = append(data, chunk.Data...)
    }
    
    return data, nil
}

func UploadBlob(ctx context.Context, client pb.UploadServiceClient, data []byte) ([]byte, error) {
    stream, err := client.Upload(ctx)
    if err != nil {
        return nil, err
    }
    
    // Start
    err = stream.Send(&pb.UploadMessage{
        Message: &pb.UploadMessage_Start{
            Start: &pb.StartUpload{TotalSize: int64(len(data))},
        },
    })
    if err != nil {
        return nil, err
    }
    
    // Chunks
    chunkSize := 1024 * 1024  // 1MB
    for i := 0; i < len(data); i += chunkSize {
        end := i + chunkSize
        if end > len(data) {
            end = len(data)
        }
        
        err = stream.Send(&pb.UploadMessage{
            Message: &pb.UploadMessage_Chunk{
                Chunk: &pb.UploadChunk{
                    Data: data[i:end],
                    Offset: int64(i),
                },
            },
        })
        if err != nil {
            return nil, err
        }
    }
    
    // Complete
    err = stream.Send(&pb.UploadMessage{
        Message: &pb.UploadMessage_Complete{
            Complete: &pb.CompleteUpload{},
        },
    })
    if err != nil {
        return nil, err
    }
    
    // Receive completion
    for {
        resp, err := stream.Recv()
        if err == io.EOF {
            break
        }
        if err != nil {
            return nil, err
        }
        
        if complete := resp.GetComplete(); complete != nil {
            return complete.Key, nil
        }
    }
    
    return nil, io.ErrUnexpectedEOF
}
```

## Error Handling

### Status Codes

| Code | Description | Retry? |
|------|-------------|--------|
| `OK` | Success | N/A |
| `NOT_FOUND` | Blob doesn't exist | No |
| `ALREADY_EXISTS` | Key collision | No |
| `RESOURCE_EXHAUSTED` | Quota exceeded | After delay |
| `UNAVAILABLE` | Temporary failure | Yes |
| `DEADLINE_EXCEEDED` | Timeout | Yes |
| `INVALID_ARGUMENT` | Bad request | No |
| `INTERNAL` | Server error | Maybe |

### Retry Logic

```scala
def withRetry[R, E, A](
  effect: ZIO[R, StatusException, A],
  maxRetries: Int = 3
): ZIO[R, StatusException, A] =
  effect.retry(
    Schedule.exponentialBackoff(1.second, 2.0)
      && Schedule.recurs(maxRetries)
      && Schedule.recurWhile { e =>
        e.getStatus.getCode match
          case Status.Code.UNAVAILABLE => true
          case Status.Code.DEADLINE_EXCEEDED => true
          case Status.Code.RESOURCE_EXHAUSTED => true
          case _ => false
      }
  )

// Usage
val result = withRetry(getBlob(key))
```

## Authentication

### TLS

```scala
val channel = NettyChannelBuilder
  .forAddress("graviton.example.com", 443)
  .useTransportSecurity()
  .build()
```

### Metadata/Headers

```scala
val metadata = new Metadata()
metadata.put(
  Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
  s"Bearer $token"
)

val client = MetadataUtils.attachHeaders(stub, metadata)
```

## Performance Tips

- **Use streaming**: For blobs > 1MB
- **Batch stat calls**: Query metadata in bulk
- **Reuse channels**: One channel per server
- **Set deadlines**: Prevent hanging requests
- **Compress requests**: Enable gRPC compression

## See Also

- **[HTTP API](./http)** ? REST endpoints
- **[Getting Started](../guide/getting-started)** ? Quick start guide

::: tip
For large uploads, use the bidirectional streaming `Upload` RPC for better flow control!
:::
