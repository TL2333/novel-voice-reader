#!/usr/bin/env python3
"""Strict static acceptance checks for the local-AI arm64 debug APK."""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import re
import subprocess
import sys
import zipfile


EXPECTED = {
    "package": "com.tl2333.novelvoicereader",
    "version_name": "0.1.0",
    "version_code": "1",
    "min_sdk": "26",
    "target_sdk": "36",
}
MODEL_SHA256 = "bda15858163726a492d02a9a727bc263551b86ac77f90812c4b30ff41d380e26"
FORBIDDEN_PERMISSIONS = {
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
}
REQUIRED_PERMISSIONS = {"android.permission.INTERNET"}
FORBIDDEN_ABIS = {"armeabi-v7a", "x86", "x86_64"}
FORBIDDEN_NAME_PARTS = ("local.properties", ".jks", ".keystore", "keystore.properties")
FORBIDDEN_PAYLOAD_MARKERS = (
    b"C:\\code\\novel-voice-reader",
    b"D:\\a\\novel-voice-reader",
    b"/home/runner/work/novel-voice-reader",
    b"MockTts",
    b"MOCK_TTS",
    b"localhost API",
)


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_checked(command: list[str]) -> str:
    # Android build tools emit UTF-8 metadata (including the Chinese app label),
    # while older Windows Python installations default to the active GBK codepage.
    # Decode explicitly so a valid APK cannot fail verification at the I/O layer.
    completed = subprocess.run(
        command,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    output = (completed.stdout or "") + (completed.stderr or "")
    if completed.returncode != 0:
        raise AssertionError(f"Command failed ({completed.returncode}): {' '.join(command)}\n{output}")
    return output


def locate_tool(sdk_root: pathlib.Path, relative: str) -> pathlib.Path:
    exact = sdk_root / "build-tools" / "36.0.0" / relative
    if exact.exists():
        return exact
    matches = sorted((sdk_root / "build-tools").glob(f"*/{relative}"), reverse=True)
    if not matches:
        raise AssertionError(f"Android SDK tool not found: {relative}")
    return matches[0]


def parse_badging(text: str) -> dict[str, str]:
    patterns = {
        "package": r"package: name='([^']+)'",
        "version_code": r"versionCode='([^']+)'",
        "version_name": r"versionName='([^']+)'",
        "min_sdk": r"(?:minSdkVersion|sdkVersion):'([^']+)'",
        "target_sdk": r"targetSdkVersion:'([^']+)'",
    }
    values: dict[str, str] = {}
    for key, pattern in patterns.items():
        match = re.search(pattern, text)
        if not match:
            raise AssertionError(f"aapt2 badging did not report {key}")
        values[key] = match.group(1)
    return values


def find_suffix(names: list[str], suffix: str) -> str:
    matches = [name for name in names if name.endswith(suffix)]
    if not matches:
        raise AssertionError(f"APK asset is missing: {suffix}")
    return matches[0]


def scan_payload(path: pathlib.Path) -> None:
    tails = [b"" for _ in FORBIDDEN_PAYLOAD_MARKERS]
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            for index, marker in enumerate(FORBIDDEN_PAYLOAD_MARKERS):
                data = tails[index] + chunk
                if marker in data:
                    raise AssertionError(f"Forbidden payload marker found: {marker!r}")
                tails[index] = data[-max(0, len(marker) - 1) :]


def verify(apk: pathlib.Path, sdk_root: pathlib.Path, report: pathlib.Path) -> None:
    if not apk.is_file():
        raise AssertionError(f"APK does not exist: {apk}")
    if apk.stat().st_size < 110_000_000:
        raise AssertionError(f"APK is too small to contain Kokoro: {apk.stat().st_size} bytes")

    apksigner = locate_tool(sdk_root, "apksigner.bat")
    zipalign = locate_tool(sdk_root, "zipalign.exe")
    aapt2 = locate_tool(sdk_root, "aapt2.exe")
    signing_output = run_checked([str(apksigner), "verify", "--verbose", str(apk)])
    alignment_output = run_checked([str(zipalign), "-c", "-p", "4", str(apk)])
    badging = run_checked([str(aapt2), "dump", "badging", str(apk)])
    permissions = run_checked([str(aapt2), "dump", "permissions", str(apk)])

    actual = parse_badging(badging)
    for key, expected in EXPECTED.items():
        if actual[key] != expected:
            raise AssertionError(f"Unexpected {key}: {actual[key]!r}; expected {expected!r}")
    for permission in FORBIDDEN_PERMISSIONS:
        if permission in permissions:
            raise AssertionError(f"Forbidden permission declared: {permission}")
    for permission in REQUIRED_PERMISSIONS:
        if permission not in permissions:
            raise AssertionError(f"Required permission is missing: {permission}")

    lines: list[str] = []
    with zipfile.ZipFile(apk) as archive:
        corrupt = archive.testzip()
        if corrupt:
            raise AssertionError(f"Corrupt APK entry: {corrupt}")
        infos = archive.infolist()
        names = [info.filename for info in infos]
        lowered = [name.lower() for name in names]
        for name in lowered:
            if any(part in name for part in FORBIDDEN_NAME_PARTS):
                raise AssertionError(f"Forbidden file packaged: {name}")

        native_abis = {
            name.split("/")[1]
            for name in names
            if name.startswith("lib/") and len(name.split("/")) >= 3
        }
        if native_abis != {"arm64-v8a"}:
            raise AssertionError(f"APK ABI set is {sorted(native_abis)}, expected only arm64-v8a")
        if native_abis & FORBIDDEN_ABIS:
            raise AssertionError(f"Forbidden ABI packaged: {sorted(native_abis & FORBIDDEN_ABIS)}")
        native_names = [name for name in names if name.startswith("lib/arm64-v8a/") and name.endswith(".so")]
        required_native_names = {
            "lib/arm64-v8a/libonnxruntime.so",
            "lib/arm64-v8a/libsherpa-onnx-c-api.so",
            "lib/arm64-v8a/libsherpa-onnx-cxx-api.so",
            "lib/arm64-v8a/libsherpa-onnx-jni.so",
        }
        missing_native = sorted(required_native_names - set(native_names))
        if missing_native:
            raise AssertionError(f"Required sherpa-onnx native libraries are missing: {missing_native}")

        model_name = find_suffix(names, "assets/kokoro/model.int8.onnx")
        find_suffix(names, "assets/kokoro/voices.bin")
        find_suffix(names, "assets/kokoro/lexicon-zh.txt")
        find_suffix(names, "assets/kokoro/phone-zh.fst")
        find_suffix(names, "assets/kokoro/date-zh.fst")
        find_suffix(names, "assets/kokoro/number-zh.fst")
        find_suffix(names, "assets/kokoro/models-manifest.json")
        find_suffix(names, "assets/mlkit-google-ocr-models/gocr/gocr_models/line_recognition_legacy_mobile/Hani_ctc_cpu.binarypb")
        find_suffix(names, "assets/mlkit-google-ocr-models/gocr/gocr_models/line_recognition_legacy_mobile/Latn_ctc_cpu.binarypb")
        find_suffix(names, "assets/testbooks/storyvoice-test.epub")
        packaged_epubs = sorted(name for name in names if name.lower().endswith(".epub"))
        if packaged_epubs != ["assets/testbooks/storyvoice-test.epub"]:
            raise AssertionError(f"Unexpected EPUB payloads packaged: {packaged_epubs}")

        model_hash = hashlib.sha256()
        with archive.open(model_name) as model_stream:
            for chunk in iter(lambda: model_stream.read(1024 * 1024), b""):
                model_hash.update(chunk)
        if model_hash.hexdigest() != MODEL_SHA256:
            raise AssertionError(f"Packaged model SHA256 mismatch: {model_hash.hexdigest()}")

        for info in infos:
            if info.filename.endswith((".onnx", ".bin", ".fst", ".wav")) and info.compress_type != zipfile.ZIP_STORED:
                raise AssertionError(f"Performance-critical asset was compressed: {info.filename}")
            lines.append(
                f"{info.file_size:12d} {info.compress_size:12d} {info.CRC:08x} {info.filename}"
            )

    scan_payload(apk)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(
        "APK static verification: PASS\n"
        f"Path: {apk.resolve()}\n"
        f"Size: {apk.stat().st_size}\n"
        f"SHA256: {sha256_file(apk)}\n"
        f"Metadata: {actual}\n"
        f"Native ABIs: {sorted(native_abis)}\n"
        f"Required native libraries: {sorted(required_native_names)}\n"
        "Network boundary permissions: INTERNET present; forbidden storage and network-state permissions absent\n"
        "Required Kokoro assets, bundled Chinese/Latin OCR models, and deterministic test EPUB: verified\n"
        f"Packaged model SHA256: {MODEL_SHA256}\n"
        f"apksigner: {signing_output.strip()}\n"
        f"zipalign: {alignment_output.strip() or 'verified'}\n"
        "\nUncompressed-size Compressed-size CRC32 Entry\n"
        + "\n".join(lines)
        + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=pathlib.Path, required=True)
    parser.add_argument("--sdk-root", type=pathlib.Path, required=True)
    parser.add_argument("--report", type=pathlib.Path, default=pathlib.Path("dist/APK_CONTENTS_REPORT.txt"))
    args = parser.parse_args()
    try:
        verify(args.apk.resolve(), args.sdk_root.resolve(), args.report.resolve())
    except (AssertionError, OSError, zipfile.BadZipFile) as error:
        print(f"APK_STATIC_VERIFICATION_FAILED: {error}", file=sys.stderr)
        return 1
    print("APK_STATIC_VERIFICATION_PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
