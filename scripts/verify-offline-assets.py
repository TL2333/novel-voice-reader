#!/usr/bin/env python3
"""Fail the build unless every pinned offline input is present and internally consistent."""

from __future__ import print_function

import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SHERPA_LOCK = ROOT / "config" / "sherpa-onnx.lock.json"
KOKORO_LOCK = ROOT / "config" / "kokoro.lock.json"
GENERATED_ASSETS = ROOT / "app" / "build" / "generated" / "offlineAssets"
TEST_EPUB = ROOT / "app" / "src" / "main" / "assets" / "testbooks" / "storyvoice-test.epub"
LICENSE_HASHES = {
    "KOKORO_LICENSE.txt": "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
    "SHERPA_ONNX_LICENSE.txt": "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
    "ONNX_RUNTIME_LICENSE.txt": "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c",
}
REQUIRED_ARM64_LIBRARIES = {
    "jni/arm64-v8a/libonnxruntime.so",
    "jni/arm64-v8a/libsherpa-onnx-c-api.so",
    "jni/arm64-v8a/libsherpa-onnx-cxx-api.so",
    "jni/arm64-v8a/libsherpa-onnx-jni.so",
}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path):
    with path.open("r", encoding="utf-8") as stream:
        return json.load(stream)


def assert_file(path, expected_size=None, expected_sha256=None):
    if not path.is_file():
        raise AssertionError("Required file is missing: {}".format(path))
    if expected_size is not None and path.stat().st_size != int(expected_size):
        raise AssertionError(
            "Size mismatch for {}: expected {}, got {}".format(
                path, expected_size, path.stat().st_size
            )
        )
    actual_hash = sha256(path) if expected_sha256 is not None else None
    if expected_sha256 is not None and actual_hash.lower() != expected_sha256.lower():
        raise AssertionError(
            "SHA-256 mismatch for {}: expected {}, got {}".format(
                path, expected_sha256, actual_hash
            )
        )


def verify_aar(lock):
    asset = lock["androidAar"]
    aar = ROOT / "app" / "libs" / asset["name"]
    assert_file(aar, asset["sizeBytes"], asset["sha256"])
    with zipfile.ZipFile(str(aar)) as archive:
        corrupt = archive.testzip()
        if corrupt:
            raise AssertionError("Corrupt AAR entry: {}".format(corrupt))
        names = set(archive.namelist())
        missing = sorted(REQUIRED_ARM64_LIBRARIES - names)
        if missing:
            raise AssertionError("AAR is missing ARM64 libraries: {}".format(missing))
        if "classes.jar" not in names or "AndroidManifest.xml" not in names:
            raise AssertionError("AAR is missing classes.jar or AndroidManifest.xml")
    return {"path": str(aar), "sizeBytes": aar.stat().st_size, "sha256": asset["sha256"]}


def safe_manifest_path(relative):
    pure = PurePosixPath(relative)
    if pure.is_absolute() or not pure.parts or any(part in ("", ".", "..") for part in pure.parts):
        raise AssertionError("Unsafe model manifest path: {}".format(relative))
    if "\\" in relative or (len(relative) >= 2 and relative[1] == ":"):
        raise AssertionError("Non-POSIX model manifest path: {}".format(relative))
    return pure


def verify_model(lock):
    model_root = GENERATED_ASSETS / lock["assetDirectory"]
    if not model_root.is_dir():
        raise AssertionError("Generated model directory is missing: {}".format(model_root))
    if model_root.is_symlink():
        raise AssertionError("Generated model directory must not be a symlink: {}".format(model_root))
    for candidate in model_root.rglob("*"):
        if candidate.is_symlink():
            raise AssertionError("Generated model payload must not contain symlinks: {}".format(candidate))

    manifest_path = model_root / "models-manifest.json"
    manifest = load_json(manifest_path)
    expected_header = {
        "schemaVersion": 1,
        "modelId": lock["modelId"],
        "modelVersion": lock["modelVersion"],
        "huggingFaceCommit": lock["huggingFaceCommit"],
        "sourceUrl": lock["sourceUrl"],
        "archiveSha256": lock["archiveSha256"],
        "fileCount": int(lock["payloadFileCount"]),
        "totalSizeBytes": int(lock["payloadSizeBytes"]),
    }
    for name, expected in expected_header.items():
        if manifest.get(name) != expected:
            raise AssertionError(
                "Manifest {} mismatch: expected {!r}, got {!r}".format(
                    name, expected, manifest.get(name)
                )
            )

    entries = manifest.get("files")
    if not isinstance(entries, list) or len(entries) != int(lock["payloadFileCount"]):
        raise AssertionError("Manifest file list has the wrong length")
    seen = set()
    verified_bytes = 0
    for entry in entries:
        relative = entry.get("path")
        if not isinstance(relative, str):
            raise AssertionError("Manifest file path is not a string")
        safe_manifest_path(relative)
        folded = relative.lower()
        if folded in seen:
            raise AssertionError("Duplicate model manifest path: {}".format(relative))
        seen.add(folded)
        path = model_root.joinpath(*PurePosixPath(relative).parts)
        assert_file(path, entry.get("sizeBytes"), entry.get("sha256"))
        verified_bytes += path.stat().st_size
    if verified_bytes != int(lock["payloadSizeBytes"]):
        raise AssertionError("Verified model byte total is {}".format(verified_bytes))

    actual_files = {
        path.relative_to(model_root).as_posix()
        for path in model_root.rglob("*")
        if path.is_file() and path.name != "models-manifest.json"
    }
    manifest_files = {entry["path"] for entry in entries}
    if actual_files != manifest_files:
        raise AssertionError(
            "Model payload/manifest differ; extra={}, missing={}".format(
                sorted(actual_files - manifest_files), sorted(manifest_files - actual_files)
            )
        )

    for relative in lock["requiredPaths"]:
        if not (model_root / relative).exists():
            raise AssertionError("Required model path is missing: {}".format(relative))
    for relative, expected in lock["requiredFiles"].items():
        assert_file(model_root / relative, expected["sizeBytes"], expected["sha256"])

    archive = ROOT / ".local-downloads" / (lock["modelId"] + ".tar.bz2")
    assert_file(archive, lock["archiveSizeBytes"], lock["archiveSha256"])
    return {
        "path": str(model_root),
        "fileCount": len(entries),
        "totalSizeBytes": verified_bytes,
        "archiveSha256": lock["archiveSha256"],
    }


def verify_epub(path):
    assert_file(path)
    with zipfile.ZipFile(str(path)) as archive:
        corrupt = archive.testzip()
        if corrupt:
            raise AssertionError("Corrupt test EPUB entry: {}".format(corrupt))
        infos = archive.infolist()
        if not infos or infos[0].filename != "mimetype":
            raise AssertionError("EPUB mimetype must be the first ZIP entry")
        if infos[0].compress_type != zipfile.ZIP_STORED:
            raise AssertionError("EPUB mimetype must be stored without compression")
        if archive.read("mimetype") != b"application/epub+zip":
            raise AssertionError("EPUB mimetype payload is invalid")
        required = {
            "META-INF/container.xml",
            "EPUB/package.opf",
            "EPUB/nav.xhtml",
            "EPUB/chapter1.xhtml",
            "EPUB/chapter2.xhtml",
            "EPUB/chapter3.xhtml",
        }
        missing = sorted(required - set(archive.namelist()))
        if missing:
            raise AssertionError("Test EPUB entries are missing: {}".format(missing))
        for name in sorted(required):
            ET.fromstring(archive.read(name).decode("utf-8"))
        package_text = archive.read("EPUB/package.opf").decode("utf-8")
        if 'version="3.0"' not in package_text:
            raise AssertionError("Test publication is not EPUB 3")
        if "META-INF/encryption.xml" in archive.namelist():
            raise AssertionError("Built-in test EPUB must not be protected")
    return {"path": str(path), "sizeBytes": path.stat().st_size, "sha256": sha256(path)}


def verify_licenses():
    root = ROOT / "app" / "src" / "main" / "assets" / "licenses"
    for name, expected_hash in LICENSE_HASHES.items():
        assert_file(root / name, expected_sha256=expected_hash)
    return dict(LICENSE_HASHES)


def main():
    try:
        sherpa = verify_aar(load_json(SHERPA_LOCK))
        model = verify_model(load_json(KOKORO_LOCK))
        epub = verify_epub(TEST_EPUB)
        licenses = verify_licenses()
    except Exception as error:
        print("OFFLINE_ASSET_VERIFICATION_FAILED: {}".format(error), file=sys.stderr)
        return 1
    print("OFFLINE_ASSETS_VERIFIED")
    print(json.dumps({"sherpaAar": sherpa, "kokoro": model, "testEpub": epub, "licenses": licenses}, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
