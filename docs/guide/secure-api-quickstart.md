# Secure API quick-start

Graviton's HTTP surface can authenticate every request with a bearer JWT.
This page walks through the fastest path to a **working, authenticated
upload + download flow** without standing up an external OIDC provider.

Two modes are supported:

| Mode      | When to use it                               | Signing  | Token source               |
|-----------|-----------------------------------------------|----------|----------------------------|
| Dev       | Local testing, CI, smoke tests                | HS256    | Built-in `/dev/token` mint |
| OIDC      | Staging / production                          | RS256    | External IdP + JWKS        |

Both modes use the same [`CallerContext`](../architecture.md) + Postgres
row-level-security enforcement, so switching between them is just a config
flip.

## 1. Dev mode (HS256 shared secret)

### 1.1 Start the server

```bash
export GRAVITON_SECURITY_ENABLED=true
export GRAVITON_SECURITY_DEV_SHARED_SECRET="change-me-any-string"
export GRAVITON_SECURITY_OIDC_ISSUER="graviton-dev"
export GRAVITON_SECURITY_OIDC_AUDIENCE="graviton"
export GRAVITON_BLOB_BACKEND="fs"
./sbt "server/run"
```

The server logs `Security: ENABLED | mode=HS256 dev shared-secret ...` at
startup. `/api/health` stays public; everything under `/api/blobs` now
requires a bearer token.

### 1.2 Mint a token

```bash
curl -s -X POST http://localhost:8081/dev/token \
  -H 'Content-Type: application/json' \
  -d '{"org_id":"00000000-0000-0000-0000-000000000001",
       "principal_id":"00000000-0000-0000-0000-000000000002"}' \
| tee /tmp/tok.json

export TOKEN=$(jq -r .access_token < /tmp/tok.json)
```

The response is OAuth-shaped:

```json
{ "access_token": "<jwt>", "expires_in": 3600, "token_type": "Bearer" }
```

The minted token carries a sensible default capability bundle
(`BlobRead`, `BlobWrite`, `BlobDelete`, `DocumentRead`, `DocumentWrite`,
`ObservabilityRead`). Override with `"caps": <bitmask>` if you need
different bits.

### 1.3 Upload + download

```bash
# Upload a local file as a blob. Response is the blob id.
BLOB_ID=$(curl -s -X POST http://localhost:8081/api/blobs \
  -H "Authorization: Bearer $TOKEN" \
  --data-binary @./some-document.pdf | jq -r .)

echo "Uploaded: $BLOB_ID"

# Stream the same bytes back.
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/blobs/$BLOB_ID" -o /tmp/roundtrip.pdf

diff ./some-document.pdf /tmp/roundtrip.pdf && echo "round-trip OK"
```

A request **without** the header fails with `401 Unauthorized`; a request
with a token that lacks `blob.read`/`blob.write` in its capability mask
fails with `403 Forbidden`.

## 2. OIDC mode (RS256 + JWKS)

For production, bind Graviton to an OIDC IdP (Okta, Auth0, Cognito,
Keycloak, etc.):

```bash
export GRAVITON_SECURITY_ENABLED=true
export GRAVITON_SECURITY_OIDC_ISSUER="https://auth.example.com"
export GRAVITON_SECURITY_OIDC_AUDIENCE="graviton"
unset GRAVITON_SECURITY_DEV_SHARED_SECRET   # production MUST NOT have this set
```

Wire a live RS256 verifier at server assembly time using the
[`zio-jwt`](https://github.com/arashi01/zio-jwt) adapter
`graviton.security.jwt.ZioJwtVerifier.fromDecoder`. The server refuses to
start if `GRAVITON_SECURITY_OIDC_ISSUER` or `GRAVITON_SECURITY_OIDC_AUDIENCE`
are missing while `GRAVITON_SECURITY_ENABLED=true`.

The tokens your IdP issues must include these claims:

| Claim          | Meaning                                           |
|----------------|---------------------------------------------------|
| `iss`          | matches `GRAVITON_SECURITY_OIDC_ISSUER`           |
| `aud`          | contains `GRAVITON_SECURITY_OIDC_AUDIENCE`        |
| `exp`, `nbf`   | standard lifetime constraints                     |
| `jti`          | unique token id (used for replay detection later) |
| `sub`          | principal UUID                                    |
| `org_id`       | tenant UUID — drives Postgres RLS isolation       |
| `scope`        | space-separated capability strings (see below)    |
| `caps`         | **or** a numeric capability bitmask               |

Recognised scope tokens: `blob.read`, `blob.write`, `blob.delete`,
`doc.read`, `doc.write`, `doc.delete`, `ns.admin`, `acl.admin`,
`observability.read`, `audit.read`, `legal_hold.write`.

## 3. What the server does with your token

1. `AuthMiddleware` extracts the bearer token and calls the configured
   `JwtVerifier`.
2. The resulting `CallerContext` is placed on the fiber via a `FiberRef`.
3. Every subsequent DB call flows through `TenantScopedDataSource`,
   which runs `SET LOCAL app.org_id = <orgId>` before executing the
   query. The Postgres RLS policies in `modules/pg/ddl.sql` then enforce
   tenant isolation — the app role has `NOBYPASSRLS`, so a buggy query
   cannot leak cross-tenant rows.
4. Every request produces an audit row through `AuditSink` with a
   per-org SHA-256 hash chain (see
   `deploy/on-prem/v1/migrations/30_audit.sql`).

## 4. Configuration reference

| Env var                                      | Default | Purpose                                         |
|----------------------------------------------|---------|-------------------------------------------------|
| `GRAVITON_SECURITY_ENABLED`                  | `false` | Turn middleware on                              |
| `GRAVITON_SECURITY_OIDC_ISSUER`              | —       | Expected `iss` claim                            |
| `GRAVITON_SECURITY_OIDC_AUDIENCE`            | —       | Expected `aud` claim                            |
| `GRAVITON_SECURITY_DEV_SHARED_SECRET`        | —       | Enables HS256 + `/dev/token`. **Never in prod.**|
| `GRAVITON_SECURITY_CLOCK_SKEW_SECONDS`       | `30`    | Slack applied to `exp`/`nbf`                    |
| `GRAVITON_SECURITY_REQUIRE_TLS`              | `false` | Refuse non-TLS listeners when `true`            |
| `GRAVITON_SECURITY_RATE_LIMIT_PER_PRINCIPAL_PER_SEC` | `100` | Per-caller token-bucket refill         |
| `GRAVITON_SECURITY_MAX_REQUEST_BYTES`        | `5 GiB` | Upload size cap                                 |
| `GRAVITON_SECURITY_KMS_KEY_ARN`              | —       | KMS ARN for S3 SSE-KMS + secrets                |

## 5. Troubleshooting

**`401 missing bearer token`** — no `Authorization: Bearer <jwt>` header
or the header is malformed. In dev mode, re-run the `/dev/token` mint.

**`401 bad JWT signature`** — the server's
`GRAVITON_SECURITY_DEV_SHARED_SECRET` differs from the one used to mint
the token. Restart the server after changing the secret.

**`401 token expired`** — tokens have a 1-hour default TTL in dev mode.
Mint a new one (the mint endpoint accepts `"ttl_seconds": <n>` to
override, capped at 24 h).

**`403 missing capability X`** — the token's scope/mask doesn't include
the capability the route requires. In dev mode, supply `"caps": <mask>`
on mint; in OIDC mode, fix the IdP's scope configuration.
