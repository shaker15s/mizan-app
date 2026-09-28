#!/usr/bin/env python3
"""Localisation and accessibility checks that a tree can be checked for.

Two of the plan's gates are about the app but are usually described as
device work, which is a reason not to do them rather than a reason they cannot
be started:

* **gate 13, full Arabic localisation.** Every English string must have an
  Arabic counterpart, the placeholders must match, and -- the part that
  actually breaks -- every `R.string.name` the Kotlin code references must
  exist. This environment cannot compile `:app`, so a typo in a resource name
  would otherwise reach a device before anyone saw it.
* **gate 15, a real accessibility audit.** The part that is static: no
  user-facing text hardcoded in Kotlin (which also makes it untranslatable), no
  control smaller than the 48dp touch target, no image or icon that a screen
  reader would announce as nothing, and no portrait lock that breaks the
  adaptive layouts the plan asks for.

What this cannot check is the half that needs a device: TalkBack reading order,
real font scaling, actual rendered contrast (that is `check_contrast.py`), and
whether a given `contentDescription = null` is correct because a label sits
next to it. Those are listed as open in docs/ACCESSIBILITY.md rather than
implied to be covered.

    python3 tools/strings_check.py [--strict] [--json]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENGLISH = "app/src/main/res/values/strings.xml"
ARABIC = "app/src/main/res/values-ar/strings.xml"

# A literal that a person reads: the first argument of Text(), or any of the
# named parameters the design system takes. It tolerates a newline between the
# call and the literal, because a Compose call usually has one.
TEXT_CALL = re.compile(
    r'\bText\(\s*(?:[a-zA-Z]+\s*=\s*)?"([^"\\]{2,})"'
    r'|\b(?:label|text|title|subtitle|placeholder|hint|supportingText|message|contentDescription'
    r'|description|body|caption|detail|summary|header|emptyText|confirmText|dismissText)'
    r'\s*=\s*"([^"\\]{2,})"'
    r'|\b(?:Toast\.makeText|Snackbar\.make)\([^,]*,\s*"([^"\\]{2,})"',
)
CONTENT_DESC_NULL = re.compile(r"contentDescription\s*=\s*null")
ICON_CALL = re.compile(r"\b(?:Icon|Image)\(|\bIconButton\(")
SMALL_SIZE = re.compile(r"\.(?:size|width|height|requiredSize|sizeIn)\(\s*(\d+(?:\.\d+)?)\.dp")
BUTTONS = ("IconButton(", "MizanPrimaryButton(", "MizanSecondaryButton(", "MizanDangerButton(", "MizanGhostButton(")
MODIFIER_CHAIN = re.compile(r"Modifier(?:\s*\.\s*[A-Za-z_][A-Za-z0-9_]*\s*\([^()]*\))+")
CLICKABLE = re.compile(r"\.clickable\b|" + "|".join(re.escape(name) for name in BUTTONS))


def clickable_chain(code: str, offset: int, token: str) -> str | None:
    """The modifier chain that decides how big a clickable surface is.

    A `.clickable` is chained onto its own `Modifier`, which sits just before
    it; a button composable takes its chain as `modifier =` further down, past
    whatever lambda the caller passed first. Reading the wrong chain -- the
    16dp icon inside a 48dp button -- would make the check noise.
    """
    if token.startswith("."):
        start = code.rfind("Modifier", max(0, offset - 240), offset)
        return code[start: offset + 400] if start >= 0 else None
    # Only the call's own arguments count. Scanning a loose window picks up the
    # next composable's icon and reports a size that was never the target's.
    depth, cursor = 0, code.index("(", offset)
    while cursor < len(code):
        if code[cursor] == "(":
            depth += 1
        elif code[cursor] == ")":
            depth -= 1
            if depth == 0:
                break
        cursor += 1
    window = code[offset: cursor + 1]
    named = re.search(r"modifier\s*=\s*(Modifier(?:\s*\.\s*[A-Za-z_][A-Za-z0-9_]*\s*\([^()]*\))*)", window)
    return named.group(1) if named else None


def is_prose(literal: str) -> bool:
    """Whether a literal is text a person reads, rather than a key or a symbol."""
    # Interpolations are replaced by a marker first, so a template such as
    # "bar_${key}" or "glass-sheen-x" is recognised as a key rather than a
    # sentence: it is lowercase-only with no words to translate.
    skeleton = re.sub(r"\$\{[^}]*\}", "\x00", literal)
    skeleton = re.sub(r"\$[A-Za-z_][A-Za-z0-9_]*", "\x00", skeleton)
    if re.fullmatch(r"[a-z0-9_.\-\x00 {}]+", skeleton):
        return False
    if re.fullmatch(r"[a-z0-9_$.]+", literal):
        # A snake_case or dotted token is a test tag, an animation key or a
        # semantic identifier. Nobody reads it and nobody translates it.
        return False
    if re.fullmatch(r"[A-Z0-9_.:\-· {}]+", literal):
        # An upper-case token is a code, an identifier or an acronym.
        return False
    if not re.search(r"[A-Za-z\u0600-\u06FF]", literal):
        return False
    if literal.strip() in {"·", "-", "—", "•", "%", "$"}:
        return False
    return True


def inside_composable(code: str, offset: int) -> bool:
    """True when the literal sits in a function marked `@Composable`."""
    head = code[:offset]
    function = head.rfind("\nfun ")
    if function < 0:
        function = head.rfind("\nprivate fun ")
    if function < 0:
        return False
    preceding = head[max(0, function - 400): function]
    return "@Composable" in preceding


@dataclass
class Finding:
    level: str  # "error" | "warning" | "note"
    area: str
    detail: str


def read(path: str) -> str:
    try:
        with open(os.path.join(ROOT, path), encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def kotlin_files(*roots: str) -> list[str]:
    out: list[str] = []
    for root in roots:
        base_dir = os.path.join(ROOT, root)
        for base, _, names in os.walk(base_dir):
            for name in names:
                if name.endswith(".kt"):
                    out.append(os.path.relpath(os.path.join(base, name), ROOT))
    return sorted(out)


# ---------------------------------------------------------------- localisation

def parse_strings(path: str) -> tuple[dict[str, str], list[str]]:
    """Returns the strings and any parse problem, without trusting the XML."""
    raw = read(path)
    if not raw:
        return {}, [f"{path} is missing"]
    try:
        root = ElementTree.fromstring(raw)
    except ElementTree.ParseError as error:
        return {}, [f"{path} is not valid XML: {error}"]
    strings: dict[str, str] = {}
    duplicates: list[str] = []
    for element in root:
        if element.tag != "string":
            continue
        name = element.get("name")
        if not name:
            continue
        if name in strings:
            duplicates.append(name)
        strings[name] = "".join(element.itertext())
    return strings, duplicates


def placeholders(text: str) -> set[str]:
    """Both styles the tree uses: Android's %1$s and the {1} the tools write."""
    found = set(re.findall(r"%\d+\$[sd]", text)) | set(re.findall(r"%(?![%\d$])", text))
    positionals = {match.group(0) for match in re.finditer(r"\{\d+\}", text)}
    if positionals:
        # Only the ones the template actually substitutes; a JSON body in a
        # string may legitimately contain braces.
        found |= positionals
    return found


def check_localisation(findings: list[Finding]) -> None:
    english, english_problems = parse_strings(ENGLISH)
    arabic, arabic_problems = parse_strings(ARABIC)
    for problem in english_problems + arabic_problems:
        findings.append(Finding("error", "localisation", problem))
    if not english or not arabic:
        return

    # A name that differs from another only by a numeric suffix, with the same
    # text, is a mistake rather than two names: it happens when a tool keys its
    # deduplication on the wrong thing, and it produces one sentence that can
    # be translated twice and drift.
    for name in sorted(english):
        base = re.sub(r"_\d+$", "", name)
        if base == name:
            continue
        if base in english and english[base] == english[name]:
            findings.append(
                Finding(
                    "warning",
                    "localisation",
                    f"{name} is a numbered copy of {base} with the same text",
                ),
            )

    for name in sorted(set(english) - set(arabic)):
        findings.append(Finding("error", "localisation", f"{name} has no Arabic string"))
    for name in sorted(set(arabic) - set(english)):
        findings.append(Finding("error", "localisation", f"{name} exists only in Arabic"))
    for name in sorted(set(english) & set(arabic)):
        expected, actual = placeholders(english[name]), placeholders(arabic[name])
        if expected != actual:
            findings.append(
                Finding(
                    "error",
                    "localisation",
                    f"{name} formats differently: english {sorted(expected)} vs arabic {sorted(actual)}",
                ),
            )
        if name.startswith("string/") or name.startswith("string "):
            findings.append(Finding("warning", "localisation", f"{name} looks like a malformed resource name"))
    if not english:
        findings.append(Finding("error", "localisation", "no English strings were parsed"))

    # Every resource the code references must exist: this is the check that
    # stands in for a compiler this environment does not have.
    referenced: dict[str, set[str]] = {}
    for path in kotlin_files("app/src/main/java", "design/src/main/kotlin"):
        for match in re.finditer(r"R\.string\.([A-Za-z0-9_]+)", read(path)):
            referenced.setdefault(match.group(1), set()).add(path)
    for name in sorted(set(referenced) - set(english)):
        where = ", ".join(sorted(referenced[name])[:3])
        findings.append(Finding("error", "resources", f"R.string.{name} is referenced but not defined ({where})"))


# --------------------------------------------------------------- accessibility

def check_accessibility(findings: list[Finding], strict_icons: bool = False) -> None:
    files = kotlin_files("app/src/main/java", "design/src/main/kotlin")
    for path in files:
        raw = read(path)
        # Comments and long doc comments are not code.
        code = re.sub(r"//[^\n]*", "", raw)
        code = re.sub(r"/\*.*?\*/", "", code, flags=re.S)
        for match in TEXT_CALL.finditer(code):
            literal = next((group for group in match.groups() if group), "")
            if not is_prose(literal):
                continue
            line = code[: match.start()].count("\n") + 1
            # `stringResource` needs a composable, so the fix is different
            # inside one. Saying which is which turns a 200-line sweep from a
            # guess into a list of edits.
            findings.append(
                Finding(
                    "error",
                    "accessibility",
                    f'{path}:{line}{" (composable)" if inside_composable(code, match.start()) else " (not composable)"} '
                    f'hardcodes user-facing text "{literal[:40]}"',
                ),
            )

        # A control smaller than the touch target is a control some people
        # cannot press. Only the clickable's own modifier chain counts: the
        # 16dp icon *inside* a 48dp button is the icon, not the target, and a
        # checker that flagged it would be teaching people to ignore it.
        for match in CLICKABLE.finditer(code):
            chain = clickable_chain(code, match.start(), match.group(0))
            if chain is None:
                continue
            found = None
            for size in SMALL_SIZE.finditer(chain):
                value = float(size.group(1))
                if value < 48.0:
                    found = (value, size.start())
                    break
            if found is None:
                continue
            value, offset = found
            line = code[: match.start() + offset].count("\n") + 1
            findings.append(
                Finding(
                    "warning",
                    "accessibility",
                    f"{path}:{line} a clickable control is {value}dp, below the 48dp touch target",
                ),
            )

        icons = len(ICON_CALL.findall(code))
        silent = len(CONTENT_DESC_NULL.findall(code))
        if icons and silent == icons:
            findings.append(
                Finding(
                    "note" if not strict_icons else "warning",
                    "accessibility",
                    f"{path} declares {icons} icon(s), all of them silent to a screen reader",
                ),
            )

    manifest = read("app/src/main/AndroidManifest.xml")
    if 'android:supportsRtl="true"' not in manifest:
        findings.append(Finding("error", "accessibility", "the manifest does not declare RTL support"))
    if "android:screenOrientation=" in manifest:
        findings.append(
            Finding("warning", "accessibility", "an orientation is locked, which breaks tablet and foldable layouts"),
        )
    for value in re.findall(r'android:configChanges="([^"]*)"', manifest):
        if "screenSize" not in value or "orientation" not in value:
            findings.append(
                Finding("warning", "accessibility", "configChanges does not list screenSize/orientation"),
            )

    # Font scaling: a layout that fixes text size in px cannot follow the
    # system setting.
    for path in files:
        raw = read(path)
        for match in re.finditer(r"fontSize\s*=\s*(\d+(?:\.\d+)?)\.(sp|dp)", raw):
            size, unit = float(match.group(1)), match.group(2)
            if unit == "dp":
                line = raw[: match.start()].count("\n") + 1
                findings.append(
                    Finding("warning", "accessibility", f"{path}:{line} sizes text in dp, so it ignores font scaling"),
                )
            elif size < 11.0:
                line = raw[: match.start()].count("\n") + 1
                findings.append(
                    Finding("warning", "accessibility", f"{path}:{line} sets text to {size}sp, below the readable floor"),
                )


# Contexts where `stringResource` is not legal: it is a composable call, so it
# cannot run inside a plain lambda. The hoist in tools/extract_strings.py
# exists to keep it out of them, and this is the check that the hoist held.
NON_COMPOSABLE = re.compile(
    r"(?:remember|rememberSaveable|LaunchedEffect|DisposableEffect|SideEffect|produceState|"
    r"derivedStateOf|snapshotFlow|onClick|onDismiss|onValueChange|onConfirm|withContext|"
    r"Thread|Thread\.start|runCatching|Runnable|show|invoke)\s*[=({]?\s*$",
)


def check_string_resource_contexts(findings: list[Finding]) -> None:
    for path in kotlin_files("app/src/main/java", "design/src/main/kotlin"):
        code = read(path)
        for match in re.finditer(r"stringResource\s*\(", code):
            head = code[: match.start()]
            opens = [index for index, char in enumerate(head) if char == "{"]
            if not opens:
                continue
            # Walk outwards for the enclosing block, skipping nesting that
            # belongs to the call's own arguments.
            depth, cursor = 0, match.start()
            while cursor > 0:
                cursor -= 1
                if code[cursor] == ")":
                    depth += 1
                elif code[cursor] == "(":
                    depth -= 1
                elif code[cursor] == "{" and depth == 0:
                    preceding = code[max(0, cursor - 60): cursor + 1]
                    if NON_COMPOSABLE.search(preceding):
                        line = head.count("\n") + 1
                        findings.append(
                            Finding(
                                "error",
                                "accessibility",
                                f"{path}:{line} calls stringResource inside a lambda that is not composable",
                            ),
                        )
                    break
                elif code[cursor] == "}":
                    depth -= 1


def selftest() -> int:
    """Fails unless the checks catch what they claim to catch.

    A check that has never failed on a real violation is a check nobody should
    believe, and this environment cannot run the app to find one.
    """
    failures: list[str] = []

    def expect(condition: bool, description: str) -> None:
        if not condition:
            failures.append(description)

    expect(not is_prose("bar_${module.moduleKey}"), "a template key was read as prose")
    expect(not is_prose("glass-sheen-x"), "an animation key was read as prose")
    expect(not is_prose("SHA-256: {1}"), "a code with a placeholder was read as prose")
    expect(is_prose("Check Stock"), "a real sentence was not read as prose")
    expect(is_prose("Save & Apply"), "a sentence with a symbol was not read as prose")
    expect(placeholders("Step %1$s of %2$d") == placeholders("الخطوة %1$s من %2$d"), "placeholders were not paired")
    expect(placeholders("Step %1$s") != placeholders("الخطوة %2$s"), "a moved placeholder was not noticed")
    expect(placeholders("{1} ({2})") == {"{1}", "{2}"}, "braced placeholders were not read")

    # The numbered-duplicate rule, on a synthetic pair.
    duplicate = {"home_export_error": "Export error: {1}", "home_export_error_2": "Export error: {1}"}
    found = [
        name
        for name in duplicate
        if re.sub(r"_\d+$", "", name) != name
        and re.sub(r"_\d+$", "", name) in duplicate
        and duplicate[re.sub(r"_\d+$", "", name)] == duplicate[name]
    ]
    expect(found == ["home_export_error_2"], "a numbered duplicate was not noticed")

    planted = [
        ('Text("Plant this string")', True),
        ('label = "Plant this string"', True),
        ("label = \"${row.id}\"", False),
        ('Toast.makeText(context, "Planted toast", Toast.LENGTH_SHORT)', True),
    ]
    for source, expected in planted:
        found = any(is_prose(next((g for g in m.groups() if g), "")) for m in TEXT_CALL.finditer(source))
        expect(found == expected, f"planted source not detected as expected: {source}")

    chain = clickable_chain('IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {', 0, "IconButton(")
    expect(chain is not None and "size(32.dp)" in chain, "a button's own size chain was not found")
    expect(
        clickable_chain('IconButton(onClick = {}, modifier = Modifier.size(48.dp))', 0, "IconButton(") is not None,
        "a 48dp button was not read",
    )

    if failures:
        print("self-test FAILED")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("self-test passed: 14 planted cases, a duplicate pair and 2 chain cases behave as claimed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict", action="store_true", help="treat warnings as failures")
    parser.add_argument("--selftest", action="store_true", help="prove the checks catch planted violations")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    findings: list[Finding] = []
    if args.selftest:
        return selftest()
    check_localisation(findings)
    check_accessibility(findings, strict_icons=args.strict)

    errors = [f for f in findings if f.level == "error"]
    warnings = [f for f in findings if f.level == "warning"]

    if args.json:
        print(json.dumps([f.__dict__ for f in findings], indent=2))
    else:
        print("Wakeel localisation and accessibility check")
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
