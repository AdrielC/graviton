# Deployment Guide

Production deployment strategies for Graviton.

## Deployment Topologies

### Single Node

Simplest setup for development or small deployments:

```mermaid
flowchart LR
    classDef edge stroke:#64748b,color:#0f172a;
    classDef compute fill:#e0f2fe,stroke:#0369a1,color:#0c4a6e;
    classDef storage fill:#ecfdf3,stroke:#047857,color:#064e3b;

    client(["Client"]):::compute
    balancer["Local/HAProxy"]:::compute
    server["Graviton Server"]:::compute
    pg["PostgreSQL"]:::storage
    rocks["RocksDB (Local Disk)"]:::storage

    client --> balancer --> server
    server --> pg
    server --> rocks
```

**Pros:**
- Simple to manage
- Low latency
- Easy debugging

**Cons:**
- Single point of failure
- Limited scalability
- No geographic distribution

### Multi-Node with Shared Storage

Scale compute separately from storage:

```mermaid
flowchart TB
    classDef edge stroke:#475569,color:#0f172a;
    classDef compute fill:#ede9fe,stroke:#6d28d9,color:#2e1065;
    classDef storage fill:#f0fdfa,stroke:#0f766e,color:#064e3b;

    client(["Clients"]):::compute
    lb["Global Load Balancer"]:::compute
    g1["Graviton #1"]:::compute
    g2["Graviton #2"]:::compute
    g3["Graviton #3"]:::compute
    s3["AWS S3 Bucket"]:::storage
    pg["PostgreSQL Cluster"]:::storage

    client --> lb
    lb --> g1
    lb --> g2
    lb --> g3
    g1 --> s3
    g2 --> s3
    g3 --> s3
    g1 --> pg
    g2 --> pg
    g3 --> pg
```

**Pros:**
- Horizontal scalability
- High availability
- Simple failover

**Cons:**
- Network latency to shared storage
- Shared database can be bottleneck

### Sharded Architecture

Partition data across nodes:

```mermaid
flowchart TB
    classDef router fill:#fef9c3,stroke:#ca8a04,color:#713f12;
    classDef shard fill:#e0f2fe,stroke:#0ea5e9,color:#0c4a6e;
    classDef storage fill:#ecfccb,stroke:#65a30d,color:#365314;

    client(["Clients"])
    router["Shard Router"]:::router
    shard1["Shard 1\n(Compute + Cache)"]:::shard
    shard2["Shard 2"]:::shard
    shard3["Shard 3"]:::shard
    bucket1["S3 Bucket 1"]:::storage
    bucket2["S3 Bucket 2"]:::storage
    bucket3["S3 Bucket 3"]:::storage

    client --> router
    router --> shard1
    router --> shard2
    router --> shard3
    shard1 --> bucket1
    shard2 --> bucket2
    shard3 --> bucket3
```

**Pros:**
- Massive scalability
- Fault isolation
- Parallel processing

**Cons:**
- Complex routing
- Cross-shard operations harder
- More operational overhead

## Infrastructure

### Docker Compose

Development and small production:

```yaml
version: '3.8'

services:
  graviton:
    image: graviton:latest
    ports:
      - "8080:8080"
    environment:
      - GRAVITON_HTTP_PORT=8080
      - GRAVITON_BLOB_BACKEND=fs
      - GRAVITON_FS_ROOT=/var/lib/graviton
      - GRAVITON_FS_BLOCK_PREFIX=cas/blocks
      - PG_JDBC_URL=jdbc:postgresql://postgres:5432/graviton
      - PG_USERNAME=graviton
      - PG_PASSWORD=${PG_PASSWORD}
    volumes:
      - graviton-data:/var/lib/graviton
    depends_on:
      - postgres
    restart: unless-stopped

  postgres:
    image: postgres:18
    environment:
      - POSTGRES_DB=graviton
      - POSTGRES_USER=graviton
      - POSTGRES_PASSWORD=${PG_PASSWORD}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    restart: unless-stopped

  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    restart: unless-stopped

volumes:
  graviton-data:
  postgres-data:
  prometheus-data:
```

### Kubernetes

Production-grade deployment:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: graviton
  labels:
    app: graviton
spec:
  replicas: 3
  selector:
    matchLabels:
      app: graviton
  template:
    metadata:
      labels:
        app: graviton
    spec:
      containers:
      - name: graviton
        image: graviton:0.1.0
        ports:
        - containerPort: 8080
          name: http
        env:
        - name: GRAVITON_BLOB_BACKEND
          value: "fs"
        - name: GRAVITON_FS_ROOT
          value: "/var/lib/graviton"
        - name: GRAVITON_FS_BLOCK_PREFIX
          value: "cas/blocks"
        - name: PG_JDBC_URL
          valueFrom:
            secretKeyRef:
              name: graviton-secrets
              key: pg-jdbc-url
        - name: PG_USERNAME
          valueFrom:
            secretKeyRef:
              name: graviton-secrets
              key: pg-username
        - name: PG_PASSWORD
          valueFrom:
            secretKeyRef:
              name: graviton-secrets
              key: pg-password
        resources:
          requests:
            memory: "2Gi"
            cpu: "1000m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
        livenessProbe:
          httpGet:
            path: /api/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /api/health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
        volumeMounts:
        - name: data
          mountPath: /var/lib/graviton
      volumes:
      - name: data
        persistentVolumeClaim:
          claimName: graviton-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: graviton
spec:
  selector:
    app: graviton
  ports:
  - name: http
    port: 80
    targetPort: 8080
  type: LoadBalancer
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: graviton-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 100Gi
```

## Configuration

### Production configuration (current server)

The current `graviton-server` reads configuration from environment variables (see `graviton.server.Main`).

Filesystem-backed blocks (simplest):

```bash
export GRAVITON_HTTP_PORT=8080
export GRAVITON_BLOB_BACKEND=fs
export GRAVITON_FS_ROOT=/var/lib/graviton
export GRAVITON_FS_BLOCK_PREFIX=cas/blocks

export PG_JDBC_URL=jdbc:postgresql://postgres:5432/graviton
export PG_USERNAME=graviton
export PG_PASSWORD=...
```

S3/MinIO-backed blocks:

```bash
export GRAVITON_HTTP_PORT=8080
export GRAVITON_BLOB_BACKEND=s3  # or minio

export QUASAR_MINIO_URL=http://minio:9000
export MINIO_ROOT_USER=...
export MINIO_ROOT_PASSWORD=...
export GRAVITON_S3_BLOCK_BUCKET=graviton-blocks
export GRAVITON_S3_BLOCK_PREFIX=cas/blocks

export PG_JDBC_URL=jdbc:postgresql://postgres:5432/graviton
export PG_USERNAME=graviton
export PG_PASSWORD=...
```

### Environment Variables

```bash
# Database
export PG_JDBC_URL="jdbc:postgresql://postgres.example.com:5432/graviton"
export PG_USERNAME="graviton"
export PG_PASSWORD="secure-password"

# Block storage selection (pick one)
export GRAVITON_BLOB_BACKEND="fs"   # or "s3" / "minio"

# Filesystem blocks
export GRAVITON_FS_ROOT="/var/lib/graviton"
export GRAVITON_FS_BLOCK_PREFIX="cas/blocks"

# MinIO/S3-compatible blocks
export QUASAR_MINIO_URL="http://minio:9000"
export MINIO_ROOT_USER="minioadmin"
export MINIO_ROOT_PASSWORD="minioadmin"
export GRAVITON_S3_BLOCK_BUCKET="graviton-blocks"
export GRAVITON_S3_BLOCK_PREFIX="cas/blocks"

# JVM
export JAVA_OPTS="-Xmx4g -Xms2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

## Monitoring

### Prometheus

`prometheus.yml`:
```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'graviton'
    static_configs:
      - targets: ['graviton:8080']
    metrics_path: '/metrics'
```

### Grafana Dashboard

Key metrics to monitor:

- **Upload rate** (`graviton_uploads_total`)
- **Download rate** (`graviton_downloads_total`)
- **Storage usage** (`graviton_storage_bytes`)
- **Replication health** (`graviton_replicas_unhealthy`)
- **Error rate** (`graviton_errors_total`)
- **Latency** (`graviton_request_duration_seconds`)

### Alerts

```yaml
groups:
  - name: graviton
    rules:
      - alert: HighErrorRate
        expr: rate(graviton_errors_total[5m]) > 0.05
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High error rate detected"
          
      - alert: LowReplicationHealth
        expr: graviton_replicas_unhealthy > 10
        for: 10m
        labels:
          severity: critical
        annotations:
          summary: "Many unhealthy replicas"
          
      - alert: DiskSpacelow
        expr: graviton_storage_bytes / graviton_storage_capacity > 0.9
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Storage nearly full"
```

## Backup & Recovery

### Database Backup

```bash
#!/bin/bash
# backup-postgres.sh

DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="graviton_backup_$DATE.sql.gz"

pg_dump -h postgres.example.com -U graviton graviton | gzip > $BACKUP_FILE

# Upload to S3
aws s3 cp $BACKUP_FILE s3://graviton-backups/postgres/

# Keep last 30 days
find . -name "graviton_backup_*.sql.gz" -mtime +30 -delete
```

### Blob Backup

Blobs in S3 are already durable, but enable versioning:

```bash
aws s3api put-bucket-versioning \
  --bucket graviton-prod \
  --versioning-configuration Status=Enabled

# Enable cross-region replication
aws s3api put-bucket-replication \
  --bucket graviton-prod \
  --replication-configuration file://replication.json
```

## Security

### TLS Configuration

```hocon
graviton {
  server {
    http {
      tls {
        enabled = true
        cert-file = "/etc/graviton/tls/cert.pem"
        key-file = "/etc/graviton/tls/key.pem"
      }
    }
    
    grpc {
      tls {
        enabled = true
        cert-chain-file = "/etc/graviton/tls/cert.pem"
        private-key-file = "/etc/graviton/tls/key.pem"
        trust-cert-collection-file = "/etc/graviton/tls/ca.pem"
      }
    }
  }
}
```

### Authentication

JWT configuration:

```hocon
graviton {
  auth {
    enabled = true
    jwt {
      issuer = "https://auth.example.com"
      audience = "graviton-api"
      jwks-url = "https://auth.example.com/.well-known/jwks.json"
    }
  }
}
```

### Network Policies

Kubernetes network policy:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: graviton-policy
spec:
  podSelector:
    matchLabels:
      app: graviton
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          role: frontend
    ports:
    - protocol: TCP
      port: 8080
  egress:
  - to:
    - podSelector:
        matchLabels:
          app: postgres
    ports:
    - protocol: TCP
      port: 5432
```

## Scaling

### Horizontal Scaling

Add more pods:

```bash
kubectl scale deployment graviton --replicas=5
```

### Vertical Scaling

Increase resources:

```bash
kubectl set resources deployment graviton \
  --requests=cpu=2,memory=4Gi \
  --limits=cpu=4,memory=8Gi
```

### Auto-scaling

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: graviton-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: graviton
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

## See Also

- **[Performance Tuning](./performance.md)** — Optimization strategies
- **[Backends](../runtime/backends.md)** — Storage configuration
- **[Installation](../guide/installation.md)** — Setup guide

::: warning
Always test your deployment in a staging environment first!
:::
