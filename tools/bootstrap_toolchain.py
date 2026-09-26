#!/usr/bin/env python3
"""Provision a JDK and a Kotlin compiler for machines that have neither.

The CI images have a JDK and Gradle. Rigid sandboxes -- the kind this
repository has been developed in -- often have nothing but Python and an
index that only speaks PyPI.  That is enough, because two artifacts are
published there:

    jdk4py                 a Temurin JDK, shipped as a wheel
    kotlin-jupyter-kernel  a wheel whose jars/ directory carries the shaded
                           Kotlin compiler together with the stdlib and
                           kotlinx.coroutines

So the verification story of this repository does not have to be "I could not
compile it here".  It can be "I compiled it, with a real kotlinc and a real
JVM, and here is the output".

What it does
    1. downloads the two wheels into a cache directory;
    2. unpacks the JDK;
    3. splits the shaded jar into a compiler jar, a stdlib jar, a reflect jar
       and a coroutines jar, and lays them out as $TOOLCHAIN/kotlinc/lib so a
       plain `kotlinc` script can front the compiler;
    4. writes $TOOLCHAIN/env.sh with JAVA_HOME and KOTLINC_HOME.

Nothing is written inside the repository except the cache directory in
`build/` (ignored by git), so a bootstrap never pollutes a diff.

Usage
    python3 tools/bootstrap_toolchain.py            # provision, print paths
    python3 tools/bootstrap_toolchain.py --check    # exit 0 when already there
    python3 tools/bootstrap_toolchain.py --prefix /tmp/tc

Exit codes: 0 on success, 2 when the toolchain cannot be provisioned (no
network, no pip, no index) -- callers should then report the check as skipped
rather than as passing.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

JDK_WHEEL = "jdk4py==17.0.9.2"
KOTLIN_WHEEL = "kotlin-jupyter-kernel"

# The shaded jar carries these; they are separated so that a compiler run can
# put exactly the stdlib and coroutines on the classpath of the code it builds,
# the way Gradle would.
SPLIT_JARS = {
    "kotlin-stdlib.jar": ("kotlin/", "kotlinx/annotations/", "META-INF/kotlin-stdlib"),
    "kotlinx-coroutines-core-jvm.jar": ("kotlinx/coroutines/",),
    "kotlin-reflect.jar": ("kotlin/reflect/",),
}


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, **kwargs)


def download(into: Path) -> list[Path]:
    """Fetch the two wheels. Returns the paths, newest first, or raises."""
    into.mkdir(parents=True, exist_ok=True)
    pip = [sys.executable, "-m", "pip", "download", "--no-deps", "--only-binary", ":all:", "-d", str(into)]
    for spec in (JDK_WHEEL, KOTLIN_WHEEL):
        result = run(pip + [spec])
        if result.returncode != 0:
            raise RuntimeError(f"pip download failed for {spec}:\n{result.stdout}\n{result.stderr}")
    return sorted(into.glob("*.whl"))


def unpack_jdk(wheels: list[Path], toolchain: Path) -> Path:
    jdk = toolchain / "jdk"
    java = jdk / "jdk4py" / "java-runtime" / "bin" / "java"
    if java.exists():
        return java
    for wheel in wheels:
        with zipfile.ZipFile(wheel) as archive:
            if any(name.endswith("java-runtime/bin/java") for name in archive.namelist()):
                archive.extractall(jdk)
                break
    else:
        raise RuntimeError("no JDK wheel found in the cache")
    for entry in (jdk / "jdk4py" / "java-runtime" / "bin").iterdir():
        entry.chmod(0o755)
    if not java.exists():
        raise RuntimeError(f"the JDK wheel did not contain {java}")
    return java


def split_kotlin(wheels: list[Path], toolchain: Path) -> Path:
    """Lay out a KOTLINC_HOME from the shaded kernel jar."""
    home = toolchain / "kotlinc"
    lib = home / "lib"
    compiler = lib / "kotlin-compiler.jar"
    if compiler.exists() and (lib / "kotlin-stdlib.jar").exists():
        return home
    lib.mkdir(parents=True, exist_ok=True)

    shaded = None
    for wheel in wheels:
        with zipfile.ZipFile(wheel) as archive:
            for name in archive.namelist():
                if name.endswith("-all.jar") and "kotlin-jupyter-kernel" in name:
                    shaded = lib / "kotlin-compiler.jar"
                    shaded.write_bytes(archive.read(name))
                    break
        if shaded:
            break
    if shaded is None:
        raise RuntimeError("the Kotlin wheel did not contain a shaded compiler jar")

    # Copy the packaged stdlib and reflect jars as they are, then carve the
    # coroutines classes out of the shaded jar.
    for wheel in wheels:
        with zipfile.ZipFile(wheel) as archive:
            for name in archive.namelist():
                base = os.path.basename(name)
                if base.startswith("kotlin-stdlib-") and base.endswith(".jar"):
                    (lib / "kotlin-stdlib.jar").write_bytes(archive.read(name))
                if base.startswith("kotlin-reflect-") and base.endswith(".jar"):
                    (lib / "kotlin-reflect.jar").write_bytes(archive.read(name))

    with zipfile.ZipFile(shaded) as source, zipfile.ZipFile(
        lib / "kotlinx-coroutines-core-jvm.jar", "w", zipfile.ZIP_DEFLATED
    ) as target:
        for name in source.namelist():
            if name.startswith("kotlinx/coroutines/") and name.endswith(".class"):
                target.writestr(name, source.read(name))

    script = home / "bin" / "kotlinc"
    script.parent.mkdir(parents=True, exist_ok=True)
    script.write_text(
        "#!/bin/sh\n"
        "# Generated by tools/bootstrap_toolchain.py. Fronts the shaded compiler.\n"
        'here=$(cd "$(dirname "$0")/.." && pwd)\n'
        'exec "${JAVA_HOME:-$here/../jdk/jdk4py/java-runtime}/bin/java" \\\n'
        '  -Xmx2g -cp "$here/lib/kotlin-compiler.jar" \\\n'
        "  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \\\n"
        '  -no-stdlib -no-reflect "$@"\n',
        encoding="utf-8",
    )
    script.chmod(0o755)
    return home


def provision(prefix: Path) -> dict[str, str]:
    cache = prefix / "wheels"
    wheels = sorted(cache.glob("*.whl")) if cache.is_dir() else []
    expected = ("jdk4py-17", "kotlin_jupyter_kernel-")
    if not all(any(w.name.startswith(e) for w in wheels) for e in expected):
        wheels = download(cache)
    java = unpack_jdk(wheels, prefix)
    home = split_kotlin(wheels, prefix)
    env = {
        "JAVA_HOME": str(java.parent.parent),
        "KOTLINC_HOME": str(home),
        "PATH": f"{java.parent}:{home / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}",
    }
    return env


def main() -> int:
    parser = argparse.ArgumentParser(description="Provision a JDK and kotlinc from PyPI.")
    parser.add_argument("--prefix", default=os.environ.get("MIZAN_TOOLCHAIN", "/tmp/mizan-toolchain"))
    parser.add_argument("--check", action="store_true", help="only report whether the toolchain is usable")
    parser.add_argument("--shell", action="store_true", help="print shell exports instead of a summary")
    args = parser.parse_args()

    prefix = Path(args.prefix)
    java = prefix / "jdk" / "jdk4py" / "java-runtime" / "bin" / "java"
    home = prefix / "kotlinc"
    usable = java.exists() and (home / "lib" / "kotlin-stdlib.jar").exists()

    if args.check:
        if usable and (home / "bin" / "kotlinc").exists():
            print(f"toolchain: {prefix}")
            return 0
        print("toolchain: missing")
        return 2

    if not usable:
        try:
            provision(prefix)
        except Exception as error:  # offline sandbox: report, do not pretend
            print(f"bootstrap_toolchain: cannot provision a toolchain here: {error}", file=sys.stderr)
            return 2

    exports = provision(prefix)
    if args.shell:
        for key, value in exports.items():
            print(f'export {key}="{value}"')
        return 0
    print(f"JAVA_HOME   {exports['JAVA_HOME']}")
    print(f"KOTLINC_HOME{exports['KOTLINC_HOME']}")
    version = run([str(java), "-version"])
    print("java        " + (version.stderr.strip().splitlines() or [""])[0])
    print(f"\nexport JAVA_HOME={exports['JAVA_HOME']}")
    print(f"export KOTLINC_HOME={exports['KOTLINC_HOME']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
