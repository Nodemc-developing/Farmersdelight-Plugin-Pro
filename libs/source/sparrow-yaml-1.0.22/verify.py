#!/usr/bin/env python3
"""Verify the delivered upstream archives without building or accessing the network."""
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def source_file(data):
    """Read only the JVM SourceFile attribute; this does not decompile class code."""
    position = 8
    count = struct.unpack_from(">H", data, position)[0]
    position += 2
    pool = [None] * count
    index = 1
    while index < count:
        tag = data[position]
        position += 1
        if tag == 1:
            size = struct.unpack_from(">H", data, position)[0]
            position += 2
            pool[index] = data[position:position + size].decode("utf-8", errors="replace")
            position += size
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            position += 4
        elif tag in (5, 6):
            position += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            position += 2
        elif tag == 15:
            position += 3
        else:
            raise ValueError(f"Unknown JVM constant-pool tag {tag}")
        index += 1
    position += 6
    interfaces = struct.unpack_from(">H", data, position)[0]
    position += 2 + interfaces * 2
    for unused in range(2):
        members = struct.unpack_from(">H", data, position)[0]
        position += 2
        for unused_member in range(members):
            position += 6
            attributes = struct.unpack_from(">H", data, position)[0]
            position += 2
            for unused_attribute in range(attributes):
                size = struct.unpack_from(">I", data, position + 2)[0]
                position += 6 + size
    attributes = struct.unpack_from(">H", data, position)[0]
    position += 2
    for unused in range(attributes):
        name, size = struct.unpack_from(">HI", data, position)
        position += 6
        if pool[name] == "SourceFile":
            return pool[struct.unpack_from(">H", data, position)[0]]
        position += size
    return None


def verify():
    manifest = json.loads((ROOT / "source-manifest.json").read_text(encoding="utf-8"))
    for artifact in manifest["files"]:
        actual = sha256((ROOT / artifact["name"]).read_bytes())
        if actual != artifact["sha256"]:
            raise ValueError(f"SHA-256 mismatch: {artifact['name']}")
    with zipfile.ZipFile(ROOT / manifest["sparrow_yaml"]["repository_archive"]) as repository, \
            zipfile.ZipFile(ROOT / "sparrow-yaml-1.0.22-sources.jar") as sources, \
            zipfile.ZipFile(ROOT / manifest["snake_yaml_fork"]["repository_archive"]) as fork:
        root = repository.namelist()[0]
        fork_root = fork.namelist()[0]
        matched = 0
        for name in sources.namelist():
            if not name.endswith(".java"):
                continue
            candidates = [item for item in repository.namelist()
                          if item.endswith("/src/main/java/" + name)]
            if len(candidates) != 1:
                raise ValueError(f"Source path is ambiguous/missing: {name}")
            published = sources.read(name).replace(b"\r\n", b"\n")
            original = repository.read(candidates[0]).replace(b"\r\n", b"\n")
            if published != original:
                raise ValueError(f"Published source differs from fixed commit: {name}")
            matched += 1
        fork_sources = {name.split("/src/main/java/", 1)[1]
                        for name in fork.namelist()
                        if "/src/main/java/" in name and name.endswith(".java")}
        import io
        dependency_bytes = repository.read(root + "libs/snakeyaml-engine-3.1-SNAPSHOT-forked.jar")
        if sha256(dependency_bytes) != manifest["snake_yaml_fork"]["bundled_binary_sha256"]:
            raise ValueError("Fork binary in the source repository does not match")
        with zipfile.ZipFile(io.BytesIO(dependency_bytes)) as dependency:
            pom = dependency.read("META-INF/maven/org.snakeyaml/snakeyaml-engine/pom.xml")
            if pom.replace(b"\r\n", b"\n") != fork.read(fork_root + "pom.xml").replace(b"\r\n", b"\n"):
                raise ValueError("Fork source POM differs from bundled binary POM")
            classes = 0
            for name in dependency.namelist():
                if not name.endswith(".class") or name.startswith("META-INF/"):
                    continue
                source = source_file(dependency.read(name))
                package = name.rsplit("/", 1)[0] + "/" if "/" in name else ""
                path = package + (source or "")
                if path not in fork_sources:
                    raise ValueError(f"Missing preferred-form source for fork class: {name}")
                classes += 1
        return {"status": "PASS", "delivered_file_hashes": len(manifest["files"]),
                "published_source_files_matching_fixed_commit": matched,
                "fork_class_source_file_coverage": classes,
                "fork_main_java_files": len(fork_sources),
                "rebuild_executed": False,
                "byte_for_byte_rebuild_verified": False}


if __name__ == "__main__":
    try:
        print(json.dumps(verify(), ensure_ascii=False, indent=2))
    except Exception as error:
        print(json.dumps({"status": "FAIL", "reason": str(error)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
