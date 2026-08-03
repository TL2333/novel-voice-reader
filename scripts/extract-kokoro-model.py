#!/usr/bin/env python3
"""Safely extract the pinned Kokoro tar.bz2 without trusting archive paths."""

import argparse
import os
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile


EXPECTED_ROOT = "kokoro-int8-multi-lang-v1_1"


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-root", default=EXPECTED_ROOT)
    return parser.parse_args()


def checked_relative_path(name, expected_root):
    normalized = name.replace("\\", "/")
    path = PurePosixPath(normalized)
    if path.is_absolute() or not path.parts:
        raise ValueError("Archive contains an absolute or empty path: {!r}".format(name))
    if any(part in ("", ".", "..") for part in path.parts):
        raise ValueError("Archive contains an unsafe path: {!r}".format(name))
    if path.parts[0] != expected_root:
        raise ValueError(
            "Unexpected archive root {!r}; expected {!r}".format(path.parts[0], expected_root)
        )
    return Path(*path.parts[1:])


def ensure_beneath(root, destination):
    root_text = str(root.resolve())
    destination_text = str(destination.resolve())
    if destination_text != root_text and not destination_text.startswith(root_text + os.sep):
        raise ValueError("Archive entry escapes extraction root: {}".format(destination))


def extract(archive, output, expected_root):
    archive = archive.resolve(strict=True)
    output = output.resolve()
    output_parent = output.parent
    output_parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise FileExistsError("Refusing to overwrite existing model directory: {}".format(output))

    temporary = Path(
        tempfile.mkdtemp(prefix=".{}-extracting-".format(output.name), dir=str(output_parent))
    )
    try:
        with tarfile.open(str(archive), mode="r:bz2") as source:
            members = source.getmembers()
            if not members:
                raise ValueError("Model archive is empty")

            checked = []
            for member in members:
                relative = checked_relative_path(member.name, expected_root)
                if member.issym() or member.islnk() or member.ischr() or member.isblk() or member.isfifo():
                    raise ValueError("Archive contains a link or special file: {!r}".format(member.name))
                if not (member.isdir() or member.isfile()):
                    raise ValueError("Archive contains an unsupported entry: {!r}".format(member.name))
                destination = temporary / relative
                ensure_beneath(temporary, destination)
                checked.append((member, relative, destination))

            for member, relative, destination in checked:
                if not relative.parts:
                    if not member.isdir():
                        raise ValueError("Archive root must be a directory")
                    continue
                if member.isdir():
                    destination.mkdir(parents=True, exist_ok=True)
                    continue

                destination.parent.mkdir(parents=True, exist_ok=True)
                extracted = source.extractfile(member)
                if extracted is None:
                    raise ValueError("Unable to read archive member: {!r}".format(member.name))
                with extracted, destination.open("wb") as target:
                    shutil.copyfileobj(extracted, target, length=1024 * 1024)
                if destination.stat().st_size != member.size:
                    raise ValueError("Size mismatch while extracting {!r}".format(member.name))

        os.replace(str(temporary), str(output))
    except Exception:
        shutil.rmtree(str(temporary), ignore_errors=True)
        raise


def main():
    args = parse_args()
    extract(args.archive, args.output, args.expected_root)
    print("KOKORO_EXTRACTED {}".format(args.output.resolve()))


if __name__ == "__main__":
    main()
