# Credential rotation

Rotation is a staged change with explicit prepare, verify, cutover, revoke, and rollback points. Never put a secret value in source control, image layers, Terraform variables, command arguments, logs, qualification evidence, or support tickets. Use the target secret manager and retain only key identifiers, versions, timestamps, and verification results.

## Manifest authentication key

Manifest proofs persist a refined key ID. The verifier supports one active signing key plus previous verification-only keys.

1. Generate a 32 through 64 byte key inside the approved secret workflow.
2. Add the new key and identifier as `GRAVITON_MANIFEST_INTEGRITY_KEY_ID` and `GRAVITON_MANIFEST_INTEGRITY_HMAC_KEY_BASE64`.
3. Move the former key into `GRAVITON_MANIFEST_INTEGRITY_PREVIOUS_KEYS_BASE64` as `old-id:base64`.
4. Replace one node. Prove old manifests read and a new upload records the new key ID.
5. Complete node replacement. Run byte-exact lifecycle, verification, backup, and isolated restore checks.
6. Keep the old verifier until no reachable manifest uses it. Inventory by key ID must be part of the operator record.
7. Remove the old verifier, replace nodes, and repeat the read and restore gates.

Rollback restores the former key as active while retaining the new key as previous. Never reuse an identifier for different key bytes.

## OIDC and JWKS

The identity provider owns signing-key generation and JWKS publication.

1. Publish the new public JWK while the old key remains present.
2. Wait at least the configured JWKS cache TTL and issuer propagation interval.
3. Verify a new-key token, an old-key token, wrong issuer, wrong audience, expiry, and capability denial through the real ingress.
4. Make the new key active at the identity provider.
5. Observe authentication failure rate and cache refresh behavior for one full maximum token lifetime.
6. Remove the old public key only after every old token is expired or revoked.

Rollback republishes the old public key and restores old-key signing. Graviton does not store private OIDC signing material.

## PostgreSQL runtime role

Use a second login role or password version so old and new credentials overlap.

1. Create the replacement credential with the exact constrained runtime grants. It must remain `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`.
2. Verify schema access, tenant-policy read-only access, RLS isolation, append-only audit permissions, snapshot maintenance, and denied control-plane writes.
3. Put the new credential in Secrets Manager and replace one task.
4. Observe pool acquisition, transaction errors, and readiness. Complete task replacement.
5. Revoke the old login or password and prove no active task reconnects with it.

Rollback restores the old secret version while its login remains valid. Do not revoke it before the replacement cohort is healthy.

## Redis or Valkey AUTH and ACL

Traffic quotas and distributed transfer leases fail closed when their coordinator is unavailable.

1. Create a second ACL user or provider-supported dual password with only the required command and key-prefix access.
2. Update Secrets Manager, replace one node, and verify transfer admission plus request and delivered-egress quota charges.
3. Exercise one coordinator failover while both credentials are valid.
4. Complete task replacement, then revoke the old credential.
5. Confirm lease counts drain, quota counters remain, and no authentication errors appear.

Rollback restores the prior secret while the old credential remains valid. A provider that cannot overlap credentials requires a planned write drain.

## AWS task identity, S3, and KMS

Prefer ECS task roles and short-lived AWS credentials over static access keys.

1. Add the replacement IAM policy or KMS grant without removing the current one.
2. Use policy simulation and a canary task to prove exact bucket prefixes, multipart staging cleanup, conditional block creation, quarantine inventory, restore, and KMS decrypt/encrypt.
3. Replace tasks and verify S3 call duration, failures, retries, and readiness.
4. Remove the old role policy or grant after all old tasks have stopped.

For a KMS key rotation that changes key identity, copy or rewrite provider objects only through a separately qualified, resumable migration. AWS managed annual key-material rotation does not change the KMS key ARN and does not require rewriting objects.

## Required evidence

Retain the cell, image digest, secret version identifiers, public key IDs, start and completion times, node replacement order, readiness results, byte-exact lifecycle result, failure and retry metrics, backup identifier, isolated restore result, rollback decision, and revocation confirmation. Never retain secret bytes.
