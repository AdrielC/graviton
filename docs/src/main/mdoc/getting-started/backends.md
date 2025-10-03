# Backend configuration

Graviton ships filesystem and S3 blob store implementations. Both are
configured through Typesafe Config and environment variables so they can be
swapped without code changes. This page collects the most common options and
full examples you can paste into `application.conf` files or Helm charts.

## Filesystem backend

The filesystem store keeps each block as an individual file under a root
directory. It is ideal for local development or small deployments where you
control the host volume.

### Required settings

| Key                                | Description                                                             |
|------------------------------------|-------------------------------------------------------------------------|
| `graviton.backends.fs.root`        | Absolute path that stores block files.                                  |
| `graviton.backends.fs.tmp`         | Directory for staging partially written blocks before they are sealed.  |
| `graviton.backends.fs.permissions` | Optional octal mask applied to created files and directories.           |

### Sample configuration

```hocon
include "graviton-defaults"

graviton {
  backends {
    default = "fs"

    fs {
      root = ${?GRAVITON_FS_ROOT}
      tmp  = ${?GRAVITON_FS_TMP}

      # fall back to sensible defaults when env vars are unset
      root = "/var/lib/graviton/blocks"
      tmp  = "/var/lib/graviton/tmp"

      permissions {
        directories = "750"
        files       = "640"
      }
    }
  }
}
```

Set the `GRAVITON_FS_ROOT` and `GRAVITON_FS_TMP` variables inside your shell or
systemd unit to override paths in production. The store will create any missing
folders during startup.

### Mount considerations

Mount the root directory on a filesystem that supports `fsync` and durable
rename semantics. When using Docker compose, bind mount a host directory:

```yaml
services:
  graviton:
    image: ghcr.io/quasar/graviton:latest
    volumes:
      - /srv/graviton/blocks:/var/lib/graviton/blocks
      - /srv/graviton/tmp:/var/lib/graviton/tmp
```

## S3 backend

The S3 store targets any S3-compatible object store such as AWS S3 or MinIO.
It uses a prefix layout to shard blocks across multiple subdirectories and can
optionally encrypt data before uploading it.

### Required settings

| Key                                  | Description                                                              |
|--------------------------------------|--------------------------------------------------------------------------|
| `graviton.backends.s3.endpoint`      | Base endpoint URL, e.g. `https://s3.us-east-1.amazonaws.com`.            |
| `graviton.backends.s3.region`        | AWS region or custom identifier for MinIO/compatible providers.          |
| `graviton.backends.s3.bucket`        | Bucket where block objects will be stored.                               |
| `graviton.backends.s3.credentials`   | Either static keys or a profile to resolve from the default credential chain. |
| `graviton.backends.s3.prefix`        | Optional path prefix used for multi-tenant deployments.                  |

### Sample configuration

```hocon
include "graviton-defaults"

graviton {
  backends {
    default = "s3"

    s3 {
      endpoint = ${?GRAVITON_S3_ENDPOINT}
      region   = ${?GRAVITON_S3_REGION}
      bucket   = ${?GRAVITON_S3_BUCKET}
      prefix   = ${?GRAVITON_S3_PREFIX}

      credentials {
        access-key-id     = ${?GRAVITON_S3_ACCESS_KEY}
        secret-access-key = ${?GRAVITON_S3_SECRET_KEY}
        session-token     = ${?GRAVITON_S3_SESSION_TOKEN}
      }

      encryption {
        enabled    = ${?GRAVITON_S3_ENCRYPT}
        key-id     = ${?GRAVITON_S3_KMS_KEY_ID}
        algorithm  = "AES256"
      }

      timeouts {
        connect = 5 seconds
        request = 30 seconds
      }
    }
  }
}
```

When running inside AWS, omit the `credentials` block so the default provider
chain can discover IAM roles automatically. For MinIO, point `endpoint` to the
server URL (for example `http://minio:9000`) and supply access keys via
environment variables.

### IAM policy example

Grant the Graviton service access to a dedicated bucket using the following
minimal AWS policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:AbortMultipartUpload",
        "s3:DeleteObject",
        "s3:GetObject",
        "s3:ListBucket",
        "s3:PutObject"
      ],
      "Resource": [
        "arn:aws:s3:::graviton-prod",
        "arn:aws:s3:::graviton-prod/*"
      ]
    }
  ]
}
```

### TLS and endpoint overrides

Use the `AWS_ENDPOINT_URL` environment variable or the `endpoint` config value
for non-AWS deployments. Set `graviton.backends.s3.force-path-style = true`
when the provider does not support virtual-hosted-style URLs.

## Switching at runtime

Both backends expose a ZLayer that can be swapped based on configuration:

```scala mdoc:passthrough
import graviton.objectstore.ObjectStore
import graviton.objectstore.filesystem.FileSystemObjectStore
import graviton.objectstore.s3.S3ObjectStore
import io.minio.MinioClient
import zio.ZLayer

final case class AppConfig(
  defaultBackend: String,
  fsRoot: java.nio.file.Path,
  s3Client: MinioClient,
  s3Bucket: String,
)

def objectStore(config: AppConfig): ZLayer[Any, Throwable, ObjectStore] =
  config.defaultBackend match
    case "fs" => ZLayer.fromZIO(FileSystemObjectStore.make(config.fsRoot))
    case "s3" => S3ObjectStore.layer(config.s3Client, config.s3Bucket)
    case other =>
      ZLayer.fail(new IllegalArgumentException(s"Unknown backend: $other"))
```

Integrate this layer inside your application bootstrap so you can promote
between filesystem and S3 without recompiling.
