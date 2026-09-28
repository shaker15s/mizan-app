#!/usr/bin/env python3
"""The workflow and the documentation must describe the same gates.

A file called ``.github/workflows/ci.yml`` is only evidence of anything if it
exists, runs, and runs what the documentation says it runs. Four failures are
common enough to have their own checks here, and all four are silent:

* a step calls ``python3 tools/something.py`` and the file was renamed;
* a tool grew a ``--selftest`` and the pipeline invokes it without one, so the
  check's own fixtures -- the planted violations that prove it can fail -- are
  never exercised;
* the documentation lists a job the workflow does not have, or the workflow
  has a job the documentation does not mention;
* the installable copy and the installed copy have drifted apart.

This is a structural check, not a YAML validator: there is no YAML parser on
the machines this repository is verified on, so it reads the file the way a
person does -- job names one level under ``jobs:``, and the commands of every
``run:`` step, including the multi-line ones. Anything it cannot see, it does
not claim to have checked.

    python3 tools/ci_check.py --strict
    python3 tools/ci_check.py --selftest
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

WORKFLOW_SOURCE = Path(".github/ci/ci.yml")
WORKFLOW_INSTALLED = Path(".github/workflows/ci.yml")
CI_DOC = Path("docs/CI.md")
TOOLS = Path("tools")

# The gates the plan names as the ones that must run on every push: the
# workflow has to invoke each of these tools somewhere.
GATE_COMMANDS = [
    "tools/repo_check.py",
    "tools/install_ci.py",
    "tools/ci_check.py",
    "tools/check_contrast.py",
    "tools/render_brand.py",
    "tools/strings_check.py",
    "tools/extract_strings.py",
    "tools/security_check.py",
    "tools/release_check.py",
    "tools/jvm_check.py",
    "tools/syntax_check.py",
    "tools/bootstrap_toolchain.py",
]

# A tool that ships a self-test must have that self-test invoked somewhere in
# the pipeline. A check whose fixtures never run is a check nobody has seen
# fail, and a check nobody has seen fail is a check nobody should trust.
SELFTEST_REQUIRED = [
    "check_contrast.py",
    "ci_check.py",
    "extract_strings.py",
    "install_ci.py",
    "jvm_check.py",
    "release_check.py",
    "repo_check.py",
    "security_check.py",
    "strings_check.py",
    "syntax_check.py",
]

JOB_RE = re.compile(r"^  ([a-z][a-z0-9_-]*):\s*$")
RUN_KEY_RE = re.compile(r"^(\s*)(?:- )?run:\s*(.*)$")
TOOL_RE = re.compile(r"tools/([A-Za-z0-9_]+\.py)")
DOC_JOB_RE = re.compile(r"^\|\s*`([a-z][a-z0-9_-]*)`\s*\|")


def read_workflow(root: Path) -> tuple[str, list[str], list[str]]:
    """Returns (text, job ids, run commands), read as a person would read it."""
    path = root / WORKFLOW_SOURCE
    if not path.is_file():
        raise SystemExit(f"FAIL  {WORKFLOW_SOURCE} is missing")
    text = path.read_text(encoding="utf-8")

    jobs: list[str] = []
    commands: list[str] = []
    in_jobs = False
    block_indent: int | None = None

    for line in text.splitlines():
        stripped = line.strip()

        if line.startswith("jobs:"):
            in_jobs = True
            continue
        if in_jobs and line and not line.startswith(" "):
            in_jobs = False
        if in_jobs:
            job = JOB_RE.match(line)
            if job:
                jobs.append(job.group(1))

        # A multi-line `run: |` block: everything more indented than the key.
        if block_indent is not None:
            if stripped == "" or len(line) - len(line.lstrip(" ")) > block_indent:
                commands.append(stripped)
                continue
            block_indent = None

        run = RUN_KEY_RE.match(line)
        if run:
            indent, value = len(run.group(1)), run.group(2).strip()
            if value in ("|", ">", "|-", ">-"):
                block_indent = indent
            else:
                commands.append(value)
    return text, jobs, commands


def doc_jobs(root: Path) -> list[str]:
    path = root / CI_DOC
    if not path.is_file():
        return []
    found: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = DOC_JOB_RE.match(line)
        if match:
            found.append(match.group(1))
    return found


def analyse(root: Path) -> list[str]:
    """Returns the human-readable problems; an empty list means they agree."""
    text, jobs, commands = read_workflow(root)
    shell = "\n".join(commands)
    problems: list[str] = []

    if not jobs:
        problems.append(f"{WORKFLOW_SOURCE}: no jobs found; the file or the reader is wrong")
    if "on:" not in text:
        problems.append(f"{WORKFLOW_SOURCE}: no trigger block")
    if "permissions:" not in text:
        problems.append(f"{WORKFLOW_SOURCE}: no explicit permissions block")

    for gate in GATE_COMMANDS:
        if gate not in text:
            problems.append(f"{WORKFLOW_SOURCE}: the gate command {gate} is never invoked")
        elif gate not in shell:
            problems.append(f"{WORKFLOW_SOURCE}: {gate} appears outside a run step")

    for tool in SELFTEST_REQUIRED:
        path = root / TOOLS / tool
        if not path.is_file():
            problems.append(f"tools/{tool}: listed as self-testing but the file does not exist")
            continue
        if "--selftest" not in path.read_text(encoding="utf-8"):
            continue  # nothing to invoke
        if not re.search(re.escape(tool) + r"[^\n]*--selftest", shell):
            problems.append(f"{WORKFLOW_SOURCE}: tools/{tool} has a self-test the pipeline never runs")

    documented = doc_jobs(root)
    for job in documented:
        if job not in jobs:
            problems.append(f"{CI_DOC}: documents job `{job}` but the workflow has {jobs}")
    for job in jobs:
        if job not in documented:
            problems.append(f"{CI_DOC}: the workflow has job `{job}` that the documentation does not list")

    for reference in sorted(set(TOOL_RE.findall(text))):
        if not (root / TOOLS / reference).is_file():
            problems.append(f"{WORKFLOW_SOURCE}: calls tools/{reference}, which does not exist")

    installed = root / WORKFLOW_INSTALLED
    if installed.is_file():
        source_body = (root / WORKFLOW_SOURCE).read_text(encoding="utf-8")
        if source_body.strip() not in installed.read_text(encoding="utf-8"):
            problems.append(f"{WORKFLOW_INSTALLED} is not the installed copy of {WORKFLOW_SOURCE}")
    return problems


def _fixture(root: Path, steps: list[str], doc_rows: list[str], tools_with_selftest: list[str]) -> None:
    (root / WORKFLOW_SOURCE).parent.mkdir(parents=True, exist_ok=True)
    (root / CI_DOC).parent.mkdir(parents=True, exist_ok=True)
    (root / TOOLS).mkdir(parents=True, exist_ok=True)
    body = "name: CI\non:\n  push:\npermissions:\n  contents: read\njobs:\n" + "\n".join(steps) + "\n"
    (root / WORKFLOW_SOURCE).write_text(body, encoding="utf-8")
    (root / CI_DOC).write_text(
        "| job | what it runs |\n| --- | --- |\n" + "\n".join(doc_rows) + "\n",
        encoding="utf-8",
    )
    for name in tools_with_selftest:
        (root / TOOLS / name).write_text("#!/usr/bin/env python3\n--selftest\n", encoding="utf-8")


def selftest() -> int:
    import tempfile

    failures = 0
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)

        # A consistent pair: every gate invoked, every self-test run, and the
        # documentation listing exactly the jobs that exist.
        steps = ["  gate:\n    steps:"]
        for gate in GATE_COMMANDS:
            name = Path(gate).name
            suffix = " --selftest" if name in SELFTEST_REQUIRED else ""
            steps.append(f"      - run: python3 {gate}{suffix}")
        _fixture(
            root,
            steps,
            ["| `gate` | everything |"],
            [Path(gate).name for gate in GATE_COMMANDS],
        )
        problems = analyse(root)
        if problems:
            print(f"FAIL  a consistent workflow and documentation should pass: {problems}")
            failures += 1

        # A tool the workflow calls that does not exist.
        _fixture(root, steps + ["      - run: python3 tools/renamed_away.py"], ["| `gate` | everything |"], [])
        problems = analyse(root)
        if not any("renamed_away.py" in problem for problem in problems):
            print(f"FAIL  a call to a missing tool should be reported: {problems}")
            failures += 1

        # A self-test that the pipeline never runs.
        steps_without_selftest = [
            step.replace(" --selftest", "") if "security_check" in step else step for step in steps
        ]
        _fixture(
            root,
            steps_without_selftest,
            ["| `gate` | everything |"],
            [Path(gate).name for gate in GATE_COMMANDS],
        )
        problems = analyse(root)
        if not any("security_check.py has a self-test" in problem for problem in problems):
            print(f"FAIL  an unrun self-test should be reported: {problems}")
            failures += 1

        # A documented job the workflow does not have.
        _fixture(
            root,
            steps,
            ["| `gate` | everything |", "| `l10n` | strings |"],
            [Path(gate).name for gate in GATE_COMMANDS],
        )
        problems = analyse(root)
        if not any("documents job `l10n`" in problem for problem in problems):
            print(f"FAIL  a job in the documentation but not the workflow should be reported: {problems}")
            failures += 1

        # An undocumented job.
        _fixture(
            root,
            steps + ["  extra:\n    steps:\n      - run: echo hi"],
            ["| `gate` | everything |"],
            [Path(gate).name for gate in GATE_COMMANDS],
        )
        problems = analyse(root)
        if not any("does not list" in problem for problem in problems):
            print(f"FAIL  a job the documentation omits should be reported: {problems}")
            failures += 1

        # A missing gate.
        thin = [step for step in steps if "jvm_check" not in step]
        _fixture(root, thin, ["| `gate` | everything |"], [Path(gate).name for gate in GATE_COMMANDS])
        problems = analyse(root)
        if not any("jvm_check.py is never invoked" in problem for problem in problems):
            print(f"FAIL  a gate that is never invoked should be reported: {problems}")
            failures += 1

    if failures:
        print(f"SELFTEST FAILED: {failures} case(s)")
        return 1
    print("SELFTEST OK: consistent pair, missing tool, unrun self-test, undocumented job, missing gate")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".", help="repository root")
    parser.add_argument("--strict", action="store_true", help="exit non-zero on any problem")
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    parser.add_argument("--selftest", action="store_true", help="check this script itself")
    arguments = parser.parse_args()

    if arguments.selftest:
        return selftest()

    root = Path(arguments.root)
    problems = analyse(root)
    if arguments.json:
        import json

        print(json.dumps({"problems": problems, "ok": not problems}, indent=2))
        return 1 if problems and arguments.strict else 0

    print("Wakeel CI consistency check")
    for problem in problems:
        print(f"  FAIL  {problem}")
    print(f"  problems: {len(problems)}")
    if not problems:
        print("OK: the workflow, the tools and the documentation agree.")
        return 0
    return 1 if arguments.strict else 0


if __name__ == "__main__":
    sys.exit(main())
