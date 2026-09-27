#!/usr/bin/env python3
"""Release-readiness checks for the Android delivery pipeline.

Gate 20 of the plan is "real production release pipeline". Much of a pipeline
is infrastructure this repository cannot run -- a keystore, Play Console
credentials, a runner -- but a surprising amount of it is a property of the
tree, and those are the properties this file checks. A release that is signed
with the debug key, that pins no receipt key, that was never versioned, or
whose mapping file is not kept, is a release that fails in the field or in an
audit, and every one of those is visible here before anything is built.

The environment variables are the pipeline's contract with the build:

    KEYSTORE_PATH       the upload keystore, never in the tree
    STORE_PASSWORD      its store password
    KEY_ALIAS           which key in it (the build defaults to "upload")
    KEY_PASSWORD        that key's password
    WAKEEL_API_BASE_URL the authority the build talks to, https
    WAKEEL_RECEIPT_PUBLIC_KEY / WAKEEL_RECEIPT_KEY_ID
                        the authority key this build pins for receipts

    python3 tools/release_check.py            # static checks
    python3 tools/release_check.py --env      # also require the variables
    python3 tools/release_check.py --json
"""

from __future__ import annotations

import argparse
import json
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


SECRET_NAMES = ("KEYSTORE_PATH", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
RELEASE_NAMES = ("WAKEEL_API_BASE_URL", "WAKEEL_RECEIPT_PUBLIC_KEY", "WAKEEL_RECEIPT_KEY_ID")


def read(path: str) -> str:
    try:
        with open(os.path.join(ROOT, path), encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def check_build_config(findings: list[Finding]) -> None:
    build = read("app/build.gradle.kts")
    if not build:
        findings.append(Finding("error", "build", "app/build.gradle.kts is missing"))
        return

    for required in [
        'create("production")',
        'create("staging")',
        'applicationIdSuffix = ".staging"',
        "isMinifyEnabled = true",
        "isShrinkResources = true",
        "proguardFiles(",
    ]:
        if required not in build:
            findings.append(Finding("error", "build", f"app/build.gradle.kts no longer declares: {required}"))

    # The production channel must not carry a demo flag or a demo suffix.
    production = re.search(r'create\("production"\)\s*\{(.*?)\n        \}', build, re.S)
    if not production:
        findings.append(Finding("error", "build", "the production flavor cannot be read"))
    else:
        body = production.group(1)
        if 'DEMO_MODE", "true"' in body:
            findings.append(Finding("error", "build", "the production flavor is a demo build"))
        if "applicationIdSuffix" in body:
            findings.append(Finding("error", "build", "the production flavor carries an application id suffix"))

    # A release must not silently fall back to the debug signature.
    release = re.search(r"release\s*\{(.*?)\n        \}", build, re.S)
    if release and "signingConfig = signingConfigs.getByName(\"debug\")" in release.group(1):
        findings.append(Finding("error", "release", "the release build type is signed with the debug key"))

    if "versionCode" not in build or "versionName" not in build:
        findings.append(Finding("error", "release", "the build declares no versionCode/versionName"))
    else:
        code = re.search(r"versionCode = (\d+)", build)
        version = re.search(r'versionName = "([^"]+)"', build)
        if not code or not version:
            findings.append(Finding("error", "release", "versionCode or versionName is not a literal, so it cannot be checked"))
        else:
            # Play refuses a repeated versionCode, so the number has to move
            # forward and the name has to describe the same build.
            if int(code.group(1)) < 1:
                findings.append(Finding("error", "release", "versionCode must be at least 1"))
            if not re.fullmatch(r"\d+\.\d+\.\d+", version.group(1)):
                findings.append(Finding("warning", "release", f"versionName {version.group(1)} is not semver"))

    if "bundle" in build and "enableSplit" in build:
        findings.append(Finding("note", "release", "explicit bundle split configuration is present"))
    if not os.path.exists(os.path.join(ROOT, "app", "proguard-rules.pro")):
        findings.append(Finding("warning", "release", "app/proguard-rules.pro is missing while minification is on"))


def check_secrets_never_in_tree(findings: list[Finding]) -> None:
    gitignore = read(".gitignore")
    for pattern in ["*.jks", "*.keystore", "keystore.properties", ".env", "*.p12"]:
        if pattern not in gitignore:
            findings.append(Finding("error", "release", f".gitignore does not exclude {pattern}"))
    try:
        tracked = subprocess.run(
            ["git", "ls-files"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.split()
    except (subprocess.CalledProcessError, FileNotFoundError):  # pragma: no cover
        tracked = []
    for path in tracked:
        if path.endswith((".jks", ".keystore", ".p12")) or path.endswith("keystore.properties"):
            findings.append(Finding("error", "release", f"a signing artifact is committed: {path}"))
    if not any(path.startswith(".github/dependabot") for path in tracked):
        findings.append(Finding("warning", "release", "dependency updates are not automated"))


def check_env(findings: list[Finding], required: bool) -> None:
    missing_secrets = [name for name in SECRET_NAMES if not os.environ.get(name)]
    missing_release = [name for name in RELEASE_NAMES if not os.environ.get(name)]
    if not required:
        if missing_secrets:
            findings.append(
                Finding("note", "env", f"no signing credentials in this environment ({', '.join(missing_secrets)}); the build stays unsigned, which is correct here"),
            )
        if missing_release:
            findings.append(
                Finding("note", "env", f"no release inputs set ({', '.join(missing_release)}); a production build would pin nothing and must say so"),
            )
        return
    for name in missing_secrets:
        findings.append(Finding("error", "env", f"{name} is not set, so a release could not be signed"))
    for name in missing_release:
        findings.append(Finding("error", "env", f"{name} is not set, so a release would ship an unconfigured app"))

    base = os.environ.get("WAKEEL_API_BASE_URL", "")
    if base and not base.startswith("https://"):
        findings.append(Finding("error", "env", f"WAKEEL_API_BASE_URL is not https: {base}"))
    key = os.environ.get("WAKEEL_RECEIPT_PUBLIC_KEY", "")
    if key and len(key) < 40:
        findings.append(Finding("error", "env", "WAKEEL_RECEIPT_PUBLIC_KEY does not look like an encoded public key"))
    key_id = os.environ.get("WAKEEL_RECEIPT_KEY_ID", "")
    if key_id and not re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", key_id):
        findings.append(Finding("error", "env", f"WAKEEL_RECEIPT_KEY_ID is not a plausible key id: {key_id}"))
    if key and not key_id:
        findings.append(Finding("error", "env", "a receipt key is pinned with no key id, so a rotation cannot be named"))
    if key_id and not key:
        findings.append(Finding("error", "env", "a receipt key id is set with no key, so nothing can be verified"))


def check_documentation(findings: list[Finding]) -> None:
    release_doc = read("docs/RELEASE.md")
    if not release_doc:
        findings.append(Finding("warning", "docs", "docs/RELEASE.md is missing: the pipeline has no written contract"))
        return
    for required in ["versionCode", "KEYSTORE_PATH", "WAKEEL_RECEIPT_PUBLIC_KEY", "rollback"]:
        if required not in release_doc:
            findings.append(Finding("warning", "docs", f"docs/RELEASE.md does not mention {required}"))
    if "mapping" not in release_doc.lower():
        findings.append(Finding("warning", "docs", "docs/RELEASE.md does not say what happens to the R8 mapping file"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", action="store_true", help="require the signing and release variables to be set")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    findings: list[Finding] = []
    check_build_config(findings)
    check_secrets_never_in_tree(findings)
    check_env(findings, required=args.env)
    check_documentation(findings)

    errors = [finding for finding in findings if finding.level == "error"]
    warnings = [finding for finding in findings if finding.level == "warning"]

    if args.json:
        print(json.dumps([finding.__dict__ for finding in findings], indent=2))
    else:
        print("Wakeel release check")
        print(f"  errors: {len(errors)}  warnings: {len(warnings)}")
        for finding in findings:
            print(f"  [{finding.level.upper()}] {finding.area}: {finding.detail}")
        if not findings:
            print("  no findings")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
