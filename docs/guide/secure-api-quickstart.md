# Secure API Quick Start

Graviton can protect every blob and observability request with bearer authentication, capability authorization, origin and TLS policy, request and byte budgets, and audit events.

| Mode | Use | Signature | Key source |
| --- | --- | --- | --- |
| Development | Local smoke and CI | HS256 | `GRAVITON_SECURITY_DEV_SHARED_SECRET` |
| OIDC | Staging and production | RS256 only | Remote HTTPS JWKS |

## Local authenticated proof

Start a filesystem server:

```bash
export GRAVITON_SECURITY_ENABLED=true
export GRAVITON_SECURITY_DEV_SHARED_SECRET='local-proof-secret-at-least-32-bytes'
export GRAVITON_SECURITY_OIDC_ISSUER='https://issuer.local.invalid'
export GRAVITON_SECURITY_OIDC_AUDIENCE='graviton-local'
export GRAVITON_BLOB_BACKEND=fs
./sbt "server/run"
```

Health remains public. Blob routes, stats, and metrics require a token.

Mint a local token:

```bash
TOKEN="$(
  curl -fsS -X POST \
    -H 'Content-Type: application/json' \
    -d '{}' \
    http://localhost:8081/dev/token \
  | jq -r '.access_token'
)"
```

The default development token includes blob read, write, and delete plus `observability.read`. A custom numeric capability mask can be passed as `{"caps":1}`. That example grants only `blob.read`.

Upload, retrieve, and verify real bytes:

```bash
printf 'authenticated graviton\n' > /tmp/graviton-secure-input

BLOB_ID="$(
  curl -fsS -X POST \
    -H "Authorization: Bearer $TOKEN" \
    --data-binary @/tmp/graviton-secure-input \
    http://localhost:8081/api/v1/blobs \
  | jq -r '.blob.id'
)"

curl -fsS \
  -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/v1/blobs/$BLOB_ID" \
  --output /tmp/graviton-secure-output

cmp /tmp/graviton-secure-input /tmp/graviton-secure-output

curl -fsS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/v1/blobs/$BLOB_ID/verify" \
| jq -e '.verified == true'
```

A missing or invalid token returns `401`. An authenticated token missing the route capability returns `403`.

The complete fat-JAR proof is automated:

```bash
./sbt server/assembly
./scripts/smoke-packaged-server.sh
```

## Production OIDC

```bash
export GRAVITON_SECURITY_ENABLED=true
export GRAVITON_SECURITY_OIDC_ISSUER='https://id.example.com/'
export GRAVITON_SECURITY_OIDC_AUDIENCE='graviton'
export GRAVITON_SECURITY_OIDC_JWKS_URI='https://id.example.com/.well-known/jwks.json'
unset GRAVITON_SECURITY_DEV_SHARED_SECRET
```

The server uses Nimbus JOSE + JWT to fetch and cache remote keys, refresh unknown key IDs, and verify RS256 signatures. It rejects other signature algorithms, wrong issuer or audience, missing required claims, expired tokens, future `nbf`, malformed UUID claims, and unknown signing keys.

Required identity claims are:

| Claim | Meaning |
| --- | --- |
| `iss` | Exact configured issuer |
| `aud` | Contains the configured audience |
| `exp` | Expiration time |
| `nbf` | Optional not-before time |
| `jti` | Token identifier |
| `sub` | Principal UUID |
| `org_id` | Organization UUID |
| `scope` | Space-separated capability names |
| `caps` | Optional numeric capability mask merged with scopes |

Recognized scopes are `blob.read`, `blob.write`, `blob.delete`, `doc.read`, `doc.write`, `doc.delete`, `ns.admin`, `acl.admin`, `observability.read`, `audit.read`, and `legal_hold.write`.

## Authorization backends

`GRAVITON_SECURITY_AUTHORIZATION_BACKEND=token` checks capabilities carried by the verified JWT.

`GRAVITON_SECURITY_AUTHORIZATION_BACKEND=jdbc` augments token capabilities with PostgreSQL ACL rows for resources that have an ID. Deny rows win. It requires the PostgreSQL connection variables and schema. Qualify its tenant and role configuration against the actual database deployment before enabling it.

## Transport and browser policy

Set `GRAVITON_SECURITY_REQUIRE_TLS=true` in a protected deployment. If TLS terminates at a reverse proxy, set `GRAVITON_SECURITY_TRUST_PROXY_HEADERS=true` only when that proxy removes client-supplied forwarding headers and writes the authoritative protocol.

Set exact browser origins:

```bash
export GRAVITON_SECURITY_CORS_ALLOWED_ORIGINS='https://console.example.com'
```

Requests with a different `Origin` are rejected. No wildcard or suffix matching is used. Canonical blob routes permit only validated `OPTIONS` preflights for their actual methods and supported request headers; the subsequent request still requires the bearer token.

## Limits and auditing

The server enforces per-principal request, upload-byte, and download-byte budgets. Upload size is checked while streaming and does not trust `Content-Length`.

The in-memory audit sink is useful for tests only. For durable chained events:

```bash
export GRAVITON_SECURITY_AUDIT_BACKEND=jdbc
export PG_JDBC_URL='jdbc:postgresql://postgres:5432/graviton'
export PG_USERNAME='graviton'
export PG_PASSWORD='from-a-secret-store'
```

The JDBC sink serializes each organization chain with a PostgreSQL advisory transaction lock. Each row commits the previous hash plus a canonical event payload, so later verification can detect modification or gaps.

See [Configuration Reference](./configuration-reference.md) and [Production Readiness](../ops/production-readiness.md) before exposing the service.
