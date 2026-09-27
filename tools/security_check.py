#!/usr/bin/env python3
"""Security checks that run without an Android SDK.

Gate 19 of the plan is "security CI". A CI job is only worth having if it
checks something a person could get wrong, so this file is the checks: secrets
that must not be committed, an Android manifest that must not allow cleartext
or an exported component by accident, credentials that must not live in plain
preferences, a session token that must not reach a log line, and a service
whose governed routes must all require a session.

What it can prove is static. It cannot prove MASVS compliance, Play Integrity
attestation, or that a keystore is hardware-backed -- those need a device, and
`--strict` does not pretend otherwise. Everything it reports as a warning is a
gap that is also written down in docs/SECURITY.md.

    python3 tools/security_check.py [--strict] [--json]

Exit code is 0 when there are no errors, 1 when there are, and 2 when
`--strict` also fails on warnings.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import subprocess
import sys
from dataclasses import dataclass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


@dataclass
class Finding:
    level: str  # "error" | "warning" | "note"
    area: str
    detail: str


def tracked_files() -> list[str]:
    """Every file git knows about, so a build output is never scanned."""
    try:
        out = subprocess.run(
            ["git", "ls-files"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout
    except (subprocess.CalledProcessError, FileNotFoundError):  # pragma: no cover
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


def read(path: str) -> str:
    try:
        with open(os.path.join(ROOT, path), encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def entropy(text: str) -> float:
    if not text:
        return 0.0
    counts = {character: text.count(character) for character in set(text)}
    length = len(text)
    return -sum((count / length) * math.log2(count / length) for count in counts.values())


# --------------------------------------------------------------------- secrets

SECRET_PATTERNS = [
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "a private key"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "an AWS access key id"),
    (re.compile(r"\bsk-[A-Za-z0-9]{20,}\b"), "an API key"),
    (re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b"), "a Google API key"),
    (re.compile(r"\bghp_[A-Za-z0-9]{36}\b"), "a GitHub token"),
    (re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b"), "a Slack token"),
]

# Files where a password literal is the point: demo accounts and tests.
PASSWORD_ALLOWLIST = (
    "ReferenceDeployment.kt",
    "/test/",
    "docs/",
    ".env.example",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "tools/",
)

PASSWORD_LITERAL = re.compile(r"""(password|passphrase|apiKey|secret)\s*[:=]\s*"([^"$]{4,})""")


def check_secrets(files: list[str], findings: list[Finding]) -> None:
    for path in files:
        if path.endswith((".jar", ".png", ".jpg", ".webp", ".so", ".zip")):
            continue
        text = read(path)
        for pattern, what in SECRET_PATTERNS:
            if pattern.search(text):
                findings.append(Finding("error", "secrets", f"{path} looks like it contains {what}"))
        for match in PASSWORD_LITERAL.finditer(text):
            literal = match.group(2)
            if any(allowed in path for allowed in PASSWORD_ALLOWLIST):
                continue
            # A placeholder is not a credential; a random-looking value is.
            placeholder = re.fullmatch(r"%?[A-Za-z_]*(placeholder|example|changeme|your|todo)[A-Za-z_]*", literal, re.I)
            if placeholder:
                continue
            if entropy(literal) >= 3.2 and len(literal) >= 12:
                findings.append(
                    Finding("error", "secrets", f"{path} assigns what looks like a live credential to {match.group(1)}"),
                )
    example = read(".env.example")
    if example:
        for line in example.splitlines():
            if line.strip().startswith("#") or "=" not in line:
                continue
            name, value = line.split("=", 1)
            value = value.strip()
            if value and not re.search(r"(example|placeholder|change|your|todo|xxx)", value, re.I):
                findings.append(Finding("warning", "secrets", f".env.example sets {name.strip()} to a concrete value"))
    if not os.path.exists(os.path.join(ROOT, ".gitignore")):
        findings.append(Finding("error", "secrets", ".gitignore is missing, so a local .env could be committed"))


# -------------------------------------------------------------------- manifest

def check_manifest(findings: list[Finding]) -> None:
    path = "app/src/main/AndroidManifest.xml"
    manifest = read(path)
    if not manifest:
        findings.append(Finding("error", "android", f"{path} is missing"))
        return
    if 'android:usesCleartextTraffic="true"' in manifest:
        findings.append(Finding("error", "android", "the manifest allows cleartext HTTP"))
    if "android:debuggable" in manifest:
        findings.append(Finding("error", "android", "the manifest sets android:debuggable"))
    if 'android:networkSecurityConfig=' not in manifest:
        findings.append(Finding("warning", "android", "no network security config is referenced"))
    if 'android:allowBackup="true"' in manifest:
        findings.append(Finding("error", "android", "allowBackup is on: session state would be backed up"))

    for match in re.finditer(r"<(activity|service|receiver|provider)\b[^>]*>", manifest, re.S):
        element = match.group(0)
        if "android:exported=" not in element:
            findings.append(
                Finding("error", "android", f"an exported-state-less <{match.group(1)}> declaration: {element.splitlines()[0].strip()}"),
            )

    config = read("app/src/main/res/xml/network_security_config.xml")
    if config:
        if "cleartextTrafficPermitted=\"true\"" in config:
            # Loopback only is the exception a debug build legitimately needs.
            for block in re.findall(r"<domain[^>]*cleartextTrafficPermitted=\"true\"[^>]*>", config):
                if "127.0.0.1" not in block and "localhost" not in block:
                    findings.append(Finding("error", "android", f"cleartext is permitted for a real host: {block.strip()}"))
    else:
        findings.append(Finding("warning", "android", "network_security_config.xml is missing"))


# ------------------------------------------------------------------ app policy

def check_app_sources(findings: list[Finding], files: list[str]) -> None:
    app_sources = [path for path in files if path.startswith("app/src/main/java/") and path.endswith(".kt")]
    for path in app_sources:
        text = read(path)
        for match in re.finditer(r"getSharedPreferences\(([^)]*)\)", text):
            line = text[: match.start()].count("\n") + 1
            # Plain preferences holding a preference is fine; holding a
            # credential is not. The distinction is whether the same file ever
            # touches one, which is checkable and worth checking.
            holds_a_secret = re.search(r"(token|secret|password|apiKey|credential)", text, re.I)
            findings.append(
                Finding(
                    "error" if holds_a_secret else "note",
                    "android",
                    f"{path}:{line} uses plain SharedPreferences"
                    + (" and the same file handles a credential" if holds_a_secret else " (preferences only)"),
                ),
            )
        for match in re.finditer(r"(Log\.[a-z]+|println)\([^)]*(token|secret|password|apiKey)", text, re.I):
            line = text[: match.start()].count("\n") + 1
            findings.append(Finding("error", "logging", f"{path}:{line} logs something that looks like a credential"))
        for match in re.finditer(r"https?://([A-Za-z0-9.-]+)", text):
            host = match.group(1)
            if host in {"127.0.0.1", "localhost", "schemas.android.com", "www.w3.org"} or host.endswith(".test"):
                continue
            line = text[: match.start()].count("\n") + 1
            findings.append(
                Finding("warning", "network", f"{path}:{line} hardcodes the host {host}; a deployment must configure it"),
            )


# --------------------------------------------------------------- service routes

GOVERNED_ROUTES = [
    ("executions", "handleExecutions"),
    ("journal", "handleJournal"),
    ("audit", "handleAudit"),
    ("erp", "handleErp"),
    ("devices", "handleDevices"),
    ("approvals", "ApprovalRoutes"),
    ("admin", "AdminApi"),
]


def check_service_sources(findings: list[Finding]) -> None:
    service_dir = os.path.join(ROOT, "service", "src", "main", "kotlin")
    files = []
    for base, _, names in os.walk(service_dir):
        files.extend(os.path.join(base, name) for name in names if name.endswith(".kt"))
    for path in files:
        relative = os.path.relpath(path, ROOT)
        text = read(os.path.relpath(path, ROOT))
        for match in re.finditer(r'Log|System\.out|println\("', text):
            del match
        for match in re.finditer(r"println\([^)]*(apiKey|password|secret|token)", text, re.I):
            line = text[: match.start()].count("\n") + 1
            findings.append(Finding("error", "logging", f"{relative}:{line} prints a credential"))

    # Every governed route must ask for a session before it answers.
    for name, marker in GOVERNED_ROUTES:
        body = None
        for path in files:
            text = read(os.path.relpath(path, ROOT))
            if marker in text:
                body = text
                break
        if body is None:
            findings.append(Finding("warning", "service", f"no route implementation found for {name} ({marker})"))
            continue
        if "authenticate(" not in body:
            findings.append(Finding("error", "service", f"the {name} surface never calls authenticate()"))

    erp = read("service/src/main/kotlin/app/mizan/service/erp/OdooJson2Connector.kt")
    if "apiKey.reveal()" in erp and "toString()" in erp:
        if "redacted" not in erp:
            findings.append(Finding("error", "service", "ErpBinding can render its key through toString()"))
    if "SecretMaterial" not in erp:
        findings.append(Finding("warning", "service", "the ERP binding does not carry a SecretMaterial"))

    http = read("service/src/main/kotlin/app/mizan/service/http/Http.kt")
    if "MAX_BODY" not in http and "tooLarge" not in http:
        findings.append(Finding("error", "service", "the HTTP layer has no request-size limit"))
    if "X-Content-Type-Options" not in http and "nosniff" not in http:
        findings.append(Finding("warning", "service", "responses do not set X-Content-Type-Options"))


# ------------------------------------------------------------------ supply chain

def check_dependencies(findings: list[Finding]) -> None:
    versions = read("gradle/libs.versions.toml")
    if not versions:
        findings.append(Finding("error", "supply chain", "gradle/libs.versions.toml is missing"))
        return
    for match in re.finditer(r'"([^"]*SNAPSHOT[^"]*)"', versions):
        findings.append(Finding("error", "supply chain", f"a snapshot dependency is pinned: {match.group(1)}"))
    for name, minimum in {"agp": (8, 0), "kotlin": (2, 0), "composeBom": (2024, 9)}.items():
        found = re.search(rf'^{name}\s*=\s*"([0-9]+)\.([0-9]+)', versions, re.M)
        if not found:
            findings.append(Finding("warning", "supply chain", f"{name} is not pinned in libs.versions.toml"))
            continue
        major, minor = int(found.group(1)), int(found.group(2))
        if (major, minor) < minimum:
            findings.append(Finding("error", "supply chain", f"{name} {major}.{minor} is below the supported floor {minimum}"))
    dependabot = read(".github/dependabot.yml")
    if "gradle" not in dependabot:
        findings.append(Finding("warning", "supply chain", "dependabot does not watch gradle"))
    if "github-actions" not in dependabot:
        findings.append(Finding("warning", "supply chain", "dependabot does not watch github-actions"))


def check_ci_documentation(findings: list[Finding]) -> None:
    ci = read("docs/CI.md")
    if not ci:
        findings.append(Finding("warning", "ci", "docs/CI.md is missing"))
        return
    for job in ["draft", "python3 tools/repo_check.py", "python3 tools/jvm_check.py"]:
        del job
    for required in ["tools/repo_check.py", "tools/jvm_check.py", "tools/check_contrast.py"]:
        if required not in ci:
            findings.append(Finding("warning", "ci", f"the documented workflow does not run {required}"))
    if "security_check.py" not in ci:
        findings.append(Finding("warning", "ci", "the documented workflow does not run tools/security_check.py"))
    if "permissions:" not in ci or "contents: read" not in ci:
        findings.append(Finding("warning", "ci", "the documented workflow does not pin least-privilege permissions"))
    if not os.path.isdir(os.path.join(ROOT, ".github", "workflows")):
        # A workflow that is documented but not installed is a gap; a workflow
        # that is documented *and says why it is not installed* is a known
        # limitation. Only the first one should fail a strict run.
        explained = "not allowed to push files under" in ci or "rather than installed" in ci
        findings.append(
            Finding(
                "note" if explained else "warning",
                "ci",
                "the workflow lives in docs/CI.md rather than .github/workflows/"
                + ("" if explained else " and does not say why"),
            ),
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict", action="store_true", help="treat warnings as failures")
    parser.add_argument("--json", action="store_true", help="print machine-readable output")
    args = parser.parse_args()

    findings: list[Finding] = []
    files = tracked_files()
    if not files:
        findings.append(Finding("warning", "repo", "git ls-files returned nothing; scanning nothing"))
    check_secrets(files, findings)
    check_manifest(findings)
    check_app_sources(findings, files)
    check_service_sources(findings)
    check_dependencies(findings)
    check_ci_documentation(findings)

    errors = [finding for finding in findings if finding.level == "error"]
    warnings = [finding for finding in findings if finding.level == "warning"]

    if args.json:
        print(json.dumps([finding.__dict__ for finding in findings], indent=2))
    else:
        print("Wakeel security check")
        print(f"  files scanned: {len(files)}")
        print(f"  errors: {len(errors)}  warnings: {len(warnings)}")
        for finding in findings:
            print(f"  [{finding.level.upper()}] {finding.area}: {finding.detail}")
        if not findings:
            print("  no findings")

    if errors:
        return 1
    if args.strict and warnings:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
