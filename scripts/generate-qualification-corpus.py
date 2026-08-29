#!/usr/bin/env python3
"""Generate a deterministic, checksummed CAS qualification corpus."""

from __future__ import annotations

import hashlib
import json
import pathlib
import shutil
import sys


CHUNK_BYTES = 1024 * 1024
CHUNKS = 32


def digest(path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(CHUNK_BYTES):
            value.update(block)
    return value.hexdigest()


def record(name: str, path: pathlib.Path, relationship: str) -> dict[str, object]:
    return {
        "name": name,
        "file": path.name,
        "bytes": path.stat().st_size,
        "sha256": digest(path),
        "relationship": relationship,
    }


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} <output-directory>")

    output = pathlib.Path(sys.argv[1]).resolve()
    output.mkdir(parents=True, exist_ok=True)
    base = output / "base-32m.bin"
    related = output / "sparse-edit-32m.bin"
    repeated = output / "repeated-block-32m.bin"

    with base.open("wb") as target:
        for index in range(CHUNKS):
            target.write(hashlib.shake_256(f"graviton-qualification-{index}".encode()).digest(CHUNK_BYTES))

    shutil.copyfile(base, related)
    with related.open("r+b") as target:
        for index in range(0, CHUNKS, 4):
            target.seek(index * CHUNK_BYTES)
            target.write(hashlib.shake_256(f"graviton-sparse-edit-{index}".encode()).digest(4096))

    repeated_block = hashlib.shake_256(b"graviton-repeated-block").digest(CHUNK_BYTES)
    with repeated.open("wb") as target:
        for _ in range(CHUNKS):
            target.write(repeated_block)

    manifest = {
        "schema": "graviton-qualification-corpus-v1",
        "generator": pathlib.Path(__file__).name,
        "chunkBytes": CHUNK_BYTES,
        "payloads": [
            record("base", base, "32 independent deterministic blocks"),
            record("sparse-edit", related, "base with 4 KiB changed in every fourth block"),
            record("repeated-block", repeated, "one deterministic block repeated 32 times"),
        ],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(json.dumps(manifest, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
