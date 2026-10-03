#!/usr/bin/env python3
"""Prepare a separate upstream-source work directory; never build or edit this project."""
import hashlib
import importlib.util
import json
import re
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def unpack(archive, destination):
    resolved = destination.resolve()
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            target = (resolved / entry.filename).resolve()
            if not target.is_relative_to(resolved):
                raise ValueError(f"Archive entry escapes output directory: {entry.filename}")
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f"Symbolic-link archive entry is not allowed: {entry.filename}")
        source.extractall(resolved)
        return resolved / source.namelist()[0].rstrip("/")


def main(output):
    spec = importlib.util.spec_from_file_location("verify_source_delivery", ROOT / "verify.py")
    verification = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verification)
    verification.verify()
    destination = Path(output).resolve()
    if destination.exists() and (not destination.is_dir() or any(destination.iterdir())):
        raise ValueError("Output must be a new or empty directory; existing files are never removed")
    destination.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((ROOT / "source-manifest.json").read_text(encoding="utf-8"))
    sparrow = unpack(ROOT / manifest["sparrow_yaml"]["repository_archive"], destination)
    engine = unpack(ROOT / manifest["snake_yaml_fork"]["repository_archive"], destination)
    build = sparrow / "build.gradle.kts"
    original = build.read_bytes()
    text = original.decode("utf-8")
    pattern = r'fun versionBanner\(\): String = project\.providers\.exec \{\s*commandLine\("git", "rev-parse", "--short=8", "HEAD"\)\s*\}\.standardOutput\.asText\.map \{ it\.trim\(\) \}\.getOrElse\("Unknown"\)'
    text, count = re.subn(pattern, 'fun versionBanner(): String = "e325884f"', text)
    if count != 1:
        raise ValueError("Expected fixed-commit build banner was not found exactly once")
    build.write_bytes(text.encode("utf-8"))
    wrapper = sparrow / "gradle/wrapper/gradle-wrapper.properties"
    properties = wrapper.read_text(encoding="utf-8")
    old_url = r'https\://services.gradle.org/distributions/gradle-8.11-bin.zip'
    if properties.count(old_url) != 1:
        raise ValueError("Expected upstream Gradle wrapper version was not found exactly once")
    properties = properties.replace(old_url, r'https\://services.gradle.org/distributions/gradle-9.1.0-bin.zip')
    checksum = (ROOT / "gradle-9.1.0-bin.zip.sha256").read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[0-9a-f]{64}", checksum):
        raise ValueError("Invalid delivered Gradle distribution checksum")
    properties += "\ndistributionSha256Sum=" + checksum + "\n"
    wrapper.write_text(properties, encoding="utf-8", newline="\n")
    result = {"source_archive_unchanged": True, "sparrow_source_directory": str(sparrow),
              "snake_yaml_source_directory": str(engine), "gradle_version": "9.1.0",
              "build_only_adjustments": ["Fixed archive build banner", "Compatible checksum-pinned Gradle wrapper"],
              "business_java_modified": False, "dependency_versions_modified": False,
              "build_executed": False,
              "original_build_script_sha256": hashlib.sha256(original).hexdigest(),
              "prepared_build_script_sha256": hashlib.sha256(build.read_bytes()).hexdigest()}
    (destination / "build-preparation.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python prepare-build.py NEW_OR_EMPTY_OUTPUT_DIRECTORY")
    try:
        main(sys.argv[1])
    except Exception as error:
        raise SystemExit(str(error))
