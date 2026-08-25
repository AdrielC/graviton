# ADR 0003: Deployment Profiles

## Status

Accepted for 0.1.

## Decision

Graviton documents three distinct profiles instead of presenting one vague production topology:

- embedded runtime inside one application
- single-node filesystem service with one writer and `Recreate` rollout
- shared S3 plus PostgreSQL service requiring operator qualification for concurrent processes and rolling upgrades

`ReplicatedBlockStore` is an application-level reliability primitive. It is not automatically enabled by the packaged server. The Kubernetes example therefore uses one replica with a `ReadWriteOnce` volume and does not imply high availability.

## Consequences

- a filesystem deployment cannot scale by increasing replicas against the same local volume
- an S3 plus PostgreSQL deployment has shared state but must still prove failover and rollout behavior in its real environment
- readiness checks verify required backends, while liveness only verifies the process
- deployment manifests remain examples whose ingress, egress, resources, storage class, secrets, and retention must be reviewed by the operator
