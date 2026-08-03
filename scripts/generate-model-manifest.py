#!/usr/bin/env python3
"""Generate a deterministic per-file manifest for the pinned Kokoro payload."""

import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import tempfile


MANIFEST_NAME = "models-manifest.json"


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--lock", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def collect_files(model_dir, output):
    output = output.resolve()
    result = []
    for path in model_dir.rglob("*"):
        if path.is_symlink():
            raise ValueError("Model payload must not contain symbolic links: {}".format(path))
        if not path.is_file() or path.resolve() == output:
            continue
        relative = path.relative_to(model_dir).as_posix()
        pure = PurePosixPath(relative)
        if pure.is_absolute() or ".." in pure.parts:
            raise ValueError("Unsafe payload path: {}".format(relative))
        result.append(
            {
                "path": relative,
                "sizeBytes": path.stat().st_size,
                "sha256": sha256(path),
            }
        )
    return sorted(result, key=lambda item: item["path"])


def main():
    args = parse_args()
    model_dir = args.model_dir.resolve(strict=True)
    lock = json.loads(args.lock.resolve(strict=True).read_text(encoding="utf-8"))
    output = (args.output or model_dir / MANIFEST_NAME).resolve()
    if output.parent != model_dir:
        raise ValueError("Manifest output must be directly inside the model directory")

    files = collect_files(model_dir, output)
    total_size = sum(item["sizeBytes"] for item in files)
    if len(files) != int(lock["payloadFileCount"]):
        raise ValueError("Refusing to manifest an unexpected file count")
    if total_size != int(lock["payloadSizeBytes"]):
        raise ValueError("Refusing to manifest an unexpected payload size")

    timestamp = datetime.utcnow().replace(microsecond=0).isoformat() + "Z"
    manifest = {
        "schemaVersion": 1,
        "modelId": lock["modelId"],
        "modelVersion": lock["modelVersion"],
        "huggingFaceCommit": lock["huggingFaceCommit"],
        "sourceUrl": lock["sourceUrl"],
        "archiveSha256": lock["archiveSha256"],
        "generatedAtUtc": timestamp,
        "fileCount": len(files),
        "totalSizeBytes": total_size,
        "files": files,
    }

    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=".{}-".format(output.name), suffix=".tmp", dir=str(output.parent)
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(manifest, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, str(output))
    except Exception:
        try:
            os.unlink(temporary_name)
        except OSError:
            pass
        raise

    print("KOKORO_MANIFEST_WRITTEN {}".format(output))


if __name__ == "__main__":
    main()
