#!/usr/bin/env python3
"""Repository health check for MIZAN.

Runs without a JDK, an Android SDK, or a network connection. It answers one
question honestly: does this tree still obey the invariants the project
claims in its documentation?

Checks
------
  invariants   the promises in docs/SECURITY.md and docs/ARCHITECTURE.md
  structure    every module in settings.gradle.kts exists and builds
  wrapper      the Gradle wrapper is present and not a stub
  migration    every Room index declared in code is created by MIGRATION_1_2
  hygiene      braces balance, no tabs, no trailing space, sane file length
  secrets      no credential literal outside the labeled demo accounts
  inventory    module and test counts, for the generated report

Usage
-----
    python3 tools/repo_check.py             # report and exit non-zero on errors
    python3 tools/repo_check.py --json      # machine readable
    python3 tools/repo_check.py --no-write  # do not rewrite docs/HEALTH.md

Exit codes: 0 when no error was found, 1 otherwise. Warnings do not fail.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import zipfile
from dataclasses import dataclass, field

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {".git", "build", ".gradle", ".idea", "node_modules", "out", "dist"}


@dataclass
class Finding:
    level: str  # "error" | "warning" | "info"
    check: str
    message: str
    path: str = ""

    def line(self) -> str:
        where = f" ({self.path})" if self.path else ""
        return f"[{self.level.upper():7s}] {self.check}: {self.message}{where}"


@dataclass
class Report:
    findings: list[Finding] = field(default_factory=list)
    stats: dict = field(default_factory=dict)

    def add(self, level: str, check: str, message: str, path: str = "") -> None:
        self.findings.append(Finding(level, check, message, path))

    @property
    def errors(self) -> list[Finding]:
        return [f for f in self.findings if f.level == "error"]

    @property
    def warnings(self) -> list[Finding]:
        return [f for f in self.findings if f.level == "warning"]

    def score(self) -> int:
        # 100 points, errors cost 12, warnings cost 3, floor at 0.
        return max(0, 100 - 12 * len(self.errors) - 3 * len(self.warnings))


def walk(suffixes: tuple[str, ...] = (".kt", ".kts")) -> list[str]:
    found: list[str] = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if name.endswith(suffixes):
                found.append(os.path.relpath(os.path.join(dirpath, name), ROOT))
    return sorted(found)


def read(path: str) -> str:
    with open(os.path.join(ROOT, path), encoding="utf-8") as handle:
        return handle.read()


def strip_noise(source: str) -> str:
    """Removes comments and string literals so structural checks are honest."""
    out = []
    i = 0
    n = len(source)
    while i < n:
        ch = source[i]
        if ch == "/" and i + 1 < n and source[i + 1] == "/":
            while i < n and source[i] != "\n":
                i += 1
        elif ch == "/" and i + 1 < n and source[i + 1] == "*":
            i += 2
            while i + 1 < n and not (source[i] == "*" and source[i + 1] == "/"):
                i += 1
            i += 2
        elif ch == "'":
            # A Kotlin char literal, which may itself contain a quote or a
            # backslash. Skipping it keeps the brace count honest.
            i += 1
            while i < n and source[i] != "'":
                if source[i] == "\\":
                    i += 1
                i += 1
            i += 1
        elif ch == '"':
            if i + 2 < n and source[i:i + 3] == '"""':
                i += 3
                while i + 2 < n and source[i:i + 3] != '"""':
                    i += 1
                i += 3
                # A raw string may end with a quote, which leaves a run of
                # three or more quotes. Consume the whole run so the next
                # scan does not start inside it.
                while i < n and source[i] == '"':
                    i += 1
            else:
                i += 1
                while i < n and source[i] != '"':
                    if source[i] == "\\":
                        i += 1
                    i += 1
                i += 1
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def check_structure(report: Report) -> None:
    settings = read("settings.gradle.kts")
    modules = re.findall(r'":(\w+)"', settings)
    report.stats["modules"] = modules
    for module in modules:
        build_file = os.path.join(module, "build.gradle.kts")
        if not os.path.exists(os.path.join(ROOT, build_file)):
            report.add("error", "structure", f"module :{module} has no build file", build_file)
    # The server module must never be a dependency of the Android app.
    app_build = read("app/build.gradle.kts")
    if ":service" in app_build:
        report.add("error", "structure", "the app must not depend on the server module", "app/build.gradle.kts")
    for forbidden in ("project(\":service\")",):
        if forbidden in app_build:
            report.add("error", "structure", "server code must not ship in the app", "app/build.gradle.kts")


def check_wrapper(report: Report) -> None:
    jar = os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.jar")
    if not os.path.exists(jar):
        report.add("error", "wrapper", "gradle-wrapper.jar is missing", "gradle/wrapper")
        return
    try:
        with zipfile.ZipFile(jar) as archive:
            names = archive.namelist()
    except zipfile.BadZipFile:
        report.add("error", "wrapper", "gradle-wrapper.jar is not a zip", "gradle/wrapper")
        return
    if "org/gradle/wrapper/GradleWrapperMain.class" not in names:
        report.add("error", "wrapper", "jar has no GradleWrapperMain", "gradle/wrapper")
    for script in ("gradlew", "gradlew.bat"):
        if not os.path.exists(os.path.join(ROOT, script)):
            report.add("error", "wrapper", f"{script} is missing", script)
    properties = read("gradle/wrapper/gradle-wrapper.properties")
    url = re.search(r"distributionUrl=(.+)$", properties, re.M)
    if not url or "services.gradle.org" not in url.group(1):
        report.add("error", "wrapper", "distributionUrl is not the official Gradle service", "gradle/wrapper")
    if os.path.exists(os.path.join(ROOT, "gradlew")):
        mode = os.stat(os.path.join(ROOT, "gradlew")).st_mode
        if not mode & 0o111:
            report.add("warning", "wrapper", "gradlew is not executable", "gradlew")


def check_invariants(report: Report) -> None:
    forbidden = {
        "com.example": "the prototype package must stay deleted",
        "fallbackToDestructiveMigration": "history must never be dropped to migrate",
        "Log.d(": "engineering logs must go through MizanLog",
        "println(": "no standard output in app or library code",
        "System.out.print": "no standard output in app or library code",
    }
    log_utility = "app/src/main/java/app/mizan/log/MizanLog.kt"
    for path in walk():
        text = read(path)
        if "/test/" in path or path.startswith("service/"):
            continue
        # MizanLog is the one place allowed to call Log directly, and the
        # reference service is a console application.
        if path == log_utility:
            continue
        for needle, why in forbidden.items():
            if needle in text:
                report.add("error", "invariants", f"{needle} found: {why}", path)
    manifest = read("app/src/main/AndroidManifest.xml")
    if 'usesCleartextTraffic="true"' in manifest:
        report.add("error", "invariants", "cleartext traffic is enabled", "AndroidManifest.xml")
    security_config = read("app/src/main/res/xml/network_security_config.xml")
    if 'cleartextTrafficPermitted="true"' in security_config:
        report.add("error", "invariants", "network config permits cleartext", "network_security_config.xml")


def check_migration(report: Report) -> None:
    entities = read("data/src/main/kotlin/app/mizan/data/local/Entities.kt")
    migration = read("data/src/main/kotlin/app/mizan/data/local/Migrations.kt")
    declared: list[tuple[str, str]] = []
    for match in re.finditer(r'tableName\s*=\s*"(\w+)"(.*?)\)\s*data class', entities, re.S):
        table, blob = match.group(1), match.group(2)
        for index in re.finditer(r'Index\(\s*"(\w+)"', blob):
            declared.append((table, index.group(1)))
    missing = [
        (table, column)
        for table, column in declared
        if f"index_{table}_{column}" not in migration
    ]
    for table, column in missing:
        report.add(
            "error",
            "migration",
            f"index on {table}.{column} is declared but never created in MIGRATION_1_2",
            "data/.../Migrations.kt",
        )
    report.stats["entity_indices"] = len(declared)
    report.stats["migration_indices"] = migration.count("CREATE INDEX")
    for table in ("executions", "receipts", "audit_events", "reconciliation_cases",
                  "cached_orders", "cached_customers", "cached_stock", "sync_meta"):
        if f"CREATE TABLE IF NOT EXISTS `{table}`" not in migration:
            report.add("warning", "migration", f"table {table} is not created by the migration", "Migrations.kt")


def check_hygiene(report: Report) -> None:
    long_lines = 0
    for path in walk():
        text = read(path)
        code = strip_noise(text)
        for opener, closer in (("{", "}"), ("(", ")"), ("[", "]")):
            if code.count(opener) != code.count(closer):
                report.add("error", "hygiene", f"unbalanced {opener}{closer}", path)
                break
        for number, line in enumerate(text.split("\n"), start=1):
            if "\t" in line:
                report.add("warning", "hygiene", f"tab on line {number}", path)
                break
        if text.rstrip("\n") != text.rstrip():
            report.add("warning", "hygiene", "trailing blank lines", path)
        longest = max((len(line) for line in text.split("\n")), default=0)
        if longest > 140:
            long_lines += 1
    biggest = sorted(
        ((len(read(p).split("\n")), p) for p in walk()),
        reverse=True,
    )[:3]
    report.stats["largest_files"] = [{"lines": n, "path": p} for n, p in biggest]
    report.stats["files_over_140_columns"] = long_lines
    # A screen that needs four digits of lines is a screen that hides a bug.
    for size, path in sorted(((len(read(p).split("\n")), p) for p in walk()), reverse=True):
        if size > 800:
            report.add("warning", "hygiene", f"{size} lines: split this file", path)


def check_secrets(report: Report) -> None:
    pattern = re.compile(
        r'(?:val\s+|const val\s+)?(\w*(?:password|secret|token|api_?key)\w*)\s*=\s*"([^"]{6,})"',
        re.IGNORECASE,
    )
    for path in walk():
        if "/test/" in path:
            continue
        text = read(path)
        for name, value in pattern.findall(text):
            # The reference service ships labeled demo accounts on purpose.
            if path.startswith("service/") and "demo" in value:
                report.add("info", "secrets", f"demo credential '{name}' in the reference service", path)
                continue
            if value.startswith("$") or "BuildConfig" in value:
                continue
            report.add("error", "secrets", f"credential literal assigned to '{name}'", path)


def inventory(report: Report) -> None:
    modules = report.stats.get("modules", [])
    rows = {}
    tests = 0
    sources = 0
    for module in modules:
        module_files = [p for p in walk() if p.startswith(module + "/")]
        module_tests = [p for p in module_files if "/test/" in p]
        module_sources = [p for p in module_files if "/test/" not in p]
        tests += len(module_tests)
        sources += len(module_sources)
        rows[module] = {
            "sources": len(module_sources),
            "tests": len(module_tests),
            "lines": sum(len(read(p).split("\n")) for p in module_files),
        }
    report.stats["per_module"] = rows
    report.stats["total_source_files"] = sources
    report.stats["total_test_files"] = tests
    test_functions = sum(
        len(re.findall(r"@Test\s+fun\s+(\w+)", read(p)))
        for p in walk()
        if "/test/" in p
    )
    report.stats["test_functions"] = test_functions


def render(report: Report) -> str:
    score = report.score()
    grade = "A" if score >= 95 else "B" if score >= 85 else "C" if score >= 70 else "D" if score >= 55 else "E"
    lines = [
        "# Repository health",
        "",
        "Generated by `tools/repo_check.py`. Do not edit by hand.",
        "",
        f"**Score: {score}/100 (grade {grade}).** "
        f"{len(report.errors)} error(s), {len(report.warnings)} warning(s).",
        "",
        "This report is a static check. It is not a build, and it does not prove",
        "that the app compiles or that a write reached an ERP.",
        "",
        "## Modules",
        "",
        "| module | sources | tests | lines |",
        "| --- | --- | --- | --- |",
    ]
    for module, row in sorted(report.stats.get("per_module", {}).items()):
        lines.append(f"| :{module} | {row['sources']} | {row['tests']} | {row['lines']} |")
    lines += [
        "",
        f"Test functions declared: {report.stats.get('test_functions', 0)}.",
        f"Room indexes declared: {report.stats.get('entity_indices', 0)}; "
        f"CREATE INDEX statements in the migration: {report.stats.get('migration_indices', 0)}.",
        "",
        "## Findings",
        "",
    ]
    if not report.findings:
        lines.append("None.")
    else:
        for level in ("error", "warning", "info"):
            group = [f for f in report.findings if f.level == level]
            if not group:
                continue
            lines.append(f"### {level.capitalize()} ({len(group)})")
            lines.append("")
            for finding in group:
                lines.append(f"- {finding.message}" + (f" — `{finding.path}`" if finding.path else ""))
            lines.append("")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Static health check for the MIZAN tree.")
    parser.add_argument("--json", action="store_true", help="print machine readable output")
    parser.add_argument("--no-write", action="store_true", help="do not rewrite docs/HEALTH.md")
    args = parser.parse_args()

    report = Report()
    check_structure(report)
    check_wrapper(report)
    check_invariants(report)
    check_migration(report)
    check_hygiene(report)
    check_secrets(report)
    inventory(report)

    if not args.no_write:
        with open(os.path.join(ROOT, "docs", "HEALTH.md"), "w", encoding="utf-8") as handle:
            handle.write(render(report))

    if args.json:
        print(json.dumps({
            "score": report.score(),
            "errors": len(report.errors),
            "warnings": len(report.warnings),
            "stats": report.stats,
            "findings": [f.__dict__ for f in report.findings],
        }, indent=2))
    else:
        print(f"MIZAN repository health: {report.score()}/100")
        print(f"  errors: {len(report.errors)}  warnings: {len(report.warnings)}")
        for finding in report.findings:
            print("  " + finding.line())
    return 1 if report.errors else 0


if __name__ == "__main__":
    sys.exit(main())
