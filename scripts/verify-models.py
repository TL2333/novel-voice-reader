#!/usr/bin/env python3
"""Verify the pinned Kokoro payload before it can enter Android assets."""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys


MANIFEST_NAME = "models-manifest.json"
VOICE_RECORD_BYTES = 510 * 1 * 256 * 4


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--lock", required=True, type=Path)
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


def payload_files(model_dir):
    result = []
    for path in model_dir.rglob("*"):
        if path.is_symlink():
            raise ValueError("Model payload must not contain symbolic links: {}".format(path))
        if not path.is_file() or path.name == MANIFEST_NAME:
            continue
        relative = path.relative_to(model_dir).as_posix()
        pure = PurePosixPath(relative)
        if pure.is_absolute() or ".." in pure.parts:
            raise ValueError("Unsafe payload path: {}".format(relative))
        result.append(path)
    return sorted(result, key=lambda item: item.relative_to(model_dir).as_posix())


def verify(model_dir, lock_path):
    model_dir = model_dir.resolve(strict=True)
    lock = json.loads(lock_path.read_text(encoding="utf-8"))

    for relative in lock["requiredPaths"]:
        path = model_dir / relative
        if not path.exists():
            raise ValueError("Required Kokoro path is missing: {}".format(relative))

    for directory in ("dict", "espeak-ng-data"):
        path = model_dir / directory
        if not path.is_dir() or not any(path.rglob("*")):
            raise ValueError("Required Kokoro directory is empty: {}".format(directory))

    for relative, expected in lock["requiredFiles"].items():
        path = model_dir / relative
        if not path.is_file():
            raise ValueError("Required Kokoro file is missing: {}".format(relative))
        actual_size = path.stat().st_size
        if actual_size != int(expected["sizeBytes"]):
            raise ValueError(
                "Size mismatch for {}: expected {}, got {}".format(
                    relative, expected["sizeBytes"], actual_size
                )
            )
        actual_hash = sha256(path)
        if actual_hash.lower() != expected["sha256"].lower():
            raise ValueError(
                "SHA-256 mismatch for {}: expected {}, got {}".format(
                    relative, expected["sha256"], actual_hash
                )
            )

    voices_size = (model_dir / "voices.bin").stat().st_size
    if voices_size % VOICE_RECORD_BYTES != 0:
        raise ValueError("voices.bin does not contain whole Kokoro speaker records")
    speaker_count = voices_size // VOICE_RECORD_BYTES
    if speaker_count != 103:
        raise ValueError("Expected 103 Kokoro speakers, got {}".format(speaker_count))

    files = payload_files(model_dir)
    total_size = sum(path.stat().st_size for path in files)
    if len(files) != int(lock["payloadFileCount"]):
        raise ValueError(
            "Payload file-count mismatch: expected {}, got {}".format(
                lock["payloadFileCount"], len(files)
            )
        )
    if total_size != int(lock["payloadSizeBytes"]):
        raise ValueError(
            "Payload byte-count mismatch: expected {}, got {}".format(
                lock["payloadSizeBytes"], total_size
            )
        )

    return {
        "modelDir": str(model_dir),
        "fileCount": len(files),
        "totalSizeBytes": total_size,
        "speakerCount": speaker_count,
        "modelSha256": lock["requiredFiles"]["model.int8.onnx"]["sha256"],
        "lexiconZhSha256": lock["requiredFiles"]["lexicon-zh.txt"]["sha256"],
    }


def main():
    args = parse_args()
    try:
        result = verify(args.model_dir, args.lock.resolve(strict=True))
    except Exception as error:
        print("KOKORO_VERIFY_FAILED {}".format(error), file=sys.stderr)
        return 1
    print("KOKORO_MODEL_VERIFIED")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
