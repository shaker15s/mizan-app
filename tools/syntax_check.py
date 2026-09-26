#!/usr/bin/env python3
"""Parse every Kotlin file in the repository with the real Kotlin parser.

The Android modules cannot be compiled here: AndroidX, Material 3 and the
Compose runtime live in Google's Maven repository, which this sandbox cannot
reach. What *is* reachable is a Kotlin compiler, and a compiler parses before
it resolves. So this script feeds every .kt file in the repository to kotlinc
in one pass and classifies what comes back:

    syntax      the parser could not read the file: a missing brace, a stray
                token, a broken string template. These are real defects and
                they fail the check.
    resolution  `unresolved reference 'androidx'`, `type mismatch`, and friends.
                Expected without AndroidX on the classpath, so they are counted
                and reported but do not fail the check.

Nothing here claims the UI type checks. It claims every file parses, which is
exactly the claim that can be made honestly without the SDK.

Usage
    python3 tools/syntax_check.py            # parse everything
    python3 tools/syntax_check.py --verbose  # also list resolution errors

Exit code 0 when every file parses.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE_ROOTS = [
    "app/src",
    "data/src",
    "design/src",
    "domain/src",
    "integration/src",
    "service/src",
]

# The parser is the only phase that can be trusted without AndroidX: it runs
# before resolution. Its diagnostics always read `expecting ...` or
# `unexpected token ...`. Everything else -- unresolved references, opt-in
# demands, exhaustiveness of a `when` over an unresolved type, "suspend
# function can only be called from a coroutine" once `launch` is unresolved --
# is a cascade from the missing dependencies and is counted, not failed.
PARSE_ERROR = re.compile(r"^\s*(expecting\b|unexpected token|syntax error)", re.IGNORECASE)

# Diagnostics caused by compiling every source root together.
DUPLICATE = re.compile(r"redeclaration|conflicting|duplicate (jvm|class)|already defined", re.IGNORECASE)

ERROR_LINE = re.compile(r"^(?P<file>[^:]+\.kt):(?P<line>\d+):(?P<col>\d+): error: (?P<message>.*)$")


def resolve_java() -> Path | None:
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / "java"
        if candidate.exists():
            return candidate
    found = shutil.which("java")
    if found:
        return Path(found)
    try:
        import jdk4py  # type: ignore

        return Path(jdk4py.JAVA)
    except Exception:
        return None


def resolve_kotlinc() -> Path | None:
    home = os.environ.get("KOTLINC_HOME")
    if home:
        for name in ("kotlinc", "kotlinc-jvm"):
            candidate = Path(home) / "bin" / name
            if candidate.exists():
                return candidate
    for name in ("kotlinc", "kotlinc-jvm"):
        found = shutil.which(name)
        if found:
            return Path(found)
    return None


SELF_TEST_SOURCE = """
class Broken {
    fun missingBrace(): Int {
        return 1
}
"""

def self_test(kotlinc: Path, java: Path) -> int:
    """Proves the classifier still catches a syntax error. A checker that can
    never fail is decoration, so this feeds it a file with a missing brace."""
    os.environ.setdefault("JAVA_HOME", str(java.parent.parent))
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "broken.kt"
        source.write_text(SELF_TEST_SOURCE, encoding="utf-8")
        result = subprocess.run(
            [str(kotlinc), "-nowarn", "-d", tmp, str(source)],
            capture_output=True,
            text=True,
        )
        errors = [
            line.strip()
            for line in (result.stdout + result.stderr).splitlines()
            if ERROR_LINE.match(line.strip()) and PARSE_ERROR.search(ERROR_LINE.match(line.strip()).group("message"))
        ]
    if errors:
        print(f"self test: OK, the missing brace was reported -- {errors[0]}")
        return 0
    print("self test: FAILED, a file with a missing brace was not reported")
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verbose", action="store_true", help="also print the resolution errors")
    parser.add_argument("--self-test", action="store_true", help="check the checker still catches a syntax error")
    args = parser.parse_args()

    java = resolve_java()
    kotlinc = resolve_kotlinc()
    if java is None or kotlinc is None:
        print("syntax_check: needs a JDK and the Kotlin compiler (JAVA_HOME, KOTLINC_HOME).")
        return 2
    os.environ.setdefault("JAVA_HOME", str(java.parent.parent))

    files: list[Path] = []
    for root in SOURCE_ROOTS:
        base = ROOT / root
        if base.is_dir():
            files.extend(sorted(base.rglob("*.kt")))
    if not files:
        print("syntax_check: no Kotlin sources found")
        return 2

    if args.self_test:
        return self_test(kotlinc, java)

    print(f"parsing {len(files)} Kotlin files with {kotlinc}")

    with tempfile.TemporaryDirectory() as tmp:
        # Batched: the compiler chokes on very long command lines, so it runs
        # in chunks of 60 files. Parse errors are reported per file either way.
        syntax: list[str] = []
        resolution: list[str] = []
        duplicated = 0
        for start in range(0, len(files), 60):
            chunk = files[start : start + 60]
            result = subprocess.run(
                [str(kotlinc), "-nowarn", "-jvm-target", "17", "-d", tmp, *[str(f) for f in chunk]],
                capture_output=True,
                text=True,
            )
            for line in (result.stdout + result.stderr).splitlines():
                match = ERROR_LINE.match(line.strip())
                if not match:
                    continue
                message = match.group("message")
                if DUPLICATE.search(message):
                    duplicated += 1
                elif PARSE_ERROR.search(message):
                    syntax.append(line.strip())
                else:
                    resolution.append(line.strip())

    print(f"  files parsed        : {len(files)}")
    print(f"  syntax errors       : {len(syntax)}")
    print(f"  resolution errors   : {len(resolution)} (AndroidX is not on the classpath)")
    if duplicated:
        print(f"  cross-root conflicts: {duplicated} (demo, staging and production define the same names)")

    if syntax:
        print("\nSYNTAX ERRORS -- these are real defects:")
        for line in syntax[:60]:
            print("  " + line)
        if len(syntax) > 60:
            print(f"  ... and {len(syntax) - 60} more")
        return 1

    if args.verbose:
        print("\nresolution errors (expected without the SDK):")
        for line in resolution[:40]:
            print("  " + line)
        if len(resolution) > 40:
            print(f"  ... and {len(resolution) - 40} more")

    print("\nOK: every Kotlin file parses.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
