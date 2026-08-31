# Production qualification matrix v1

`matrix.json` separates repository proof, scheduled retained proof, and target-environment acceptance. It never upgrades a local test into an AWS, Ceph, RDS, Valkey, IdP, ingress, or zone claim.

Run `./scripts/validate-qualification-contract.py` on every change. The scheduled workflow retains the matrix beside commit-addressed evidence. A target gate becomes passed only in a separate evidence record that identifies the exact account, region, image digest, topology, workload, timestamps, and artifacts. Do not edit the matrix to call a target gate repository-verified.
