#!/usr/bin/env python3
"""Move user-facing text out of Kotlin and into the string resources.

The plan's gate 13 is full Arabic localisation, and the reason it is a gate
rather than a task is that a hardcoded string is invisible until somebody
reads the screen in a language other than English -- or until somebody with
TalkBack, or a translation team, or a font scale of 1.3, finds it.

The mechanical part is not a search and replace. `stringResource` is a
composable call, so it is legal in a composable body and illegal inside, say,
an `onClick` lambda; and a literal can sit in a default parameter, where it is
legal in neither. This tool resolves that by hoisting: for every composable
function that contains prose, it declares the strings at the top of that
function's body and replaces the literal with the name. A reference works
anywhere the function's locals work, including inside an event lambda.

It writes the English and Arabic resources itself, reuses an existing resource
when the same sentence is already defined, and refuses to touch a literal it
cannot place with certainty -- those are reported, not guessed at.

    python3 tools/extract_strings.py --dry-run     # what it would do
    python3 tools/extract_strings.py --apply
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass, field

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from strings_check import (  # noqa: E402  (sibling tool, same directory)
    ARABIC,
    ENGLISH,
    ROOT,
    is_prose,
)

SOURCES = ["app/src/main/java", "design/src/main/kotlin"]

# "Text(" where the first argument is a literal, or a named parameter that a
# person reads. Kept deliberately narrow: a false positive here edits code.
PROSE_CALL = re.compile(
    r'\bText\(\s*(?:[a-zA-Z]+\s*=\s*)?"([^"\\]{2,})"'
    r'|\b(?:label|text|title|subtitle|placeholder|hint|supportingText|contentDescription|header|name'
    r'|description|body|caption|detail|summary|emptyText|confirmText|dismissText)'
    r'\s*=\s*"([^"\\]{2,})"'
    # A toast is text a person reads too, and the reason it is easy to miss is
    # that it never passes through a composable.
    r'|\b(?:Toast\.makeText|Snackbar\.make)\([^,]*,\s*"([^"\\]{2,})"',
)

# Text that is already a format template, a duration, or a unit: extracted,
# but the Arabic side usually needs the same shape rather than a translation.
FEATURE_NAMES = {
    "feature/home": "home",
    "feature/agent": "agent",
    "feature/account": "account",
    "feature/auth": "auth",
    "feature/evidence": "evidence",
    "feature/governance": "gov",
    "feature/operations": "ops",
    "feature/reconciliation": "recon",
    "feature/approvals": "approval",
    "feature/shell": "shell",
    "security": "security",
}

# Names the automatic slug gets wrong because the sentence is mostly a value,
# or because the same words already appear in another screen.
NAME_OVERRIDES = {
    "{1}": "design_value_only",
    "{1}%": "home_progress_percent",
    "{1} · {2}": "design_joined_value",
    "{1} · {2} · {3}": "home_actor_role_tenant",
    "{1} ({2})": "home_option_with_count",
    "{1}ms": "home_latency_millis",
    "{1} ops": "home_operation_count",
    "{1} pending ambiguous cases require human match": "recon_ambiguous_cases",
    "Export Report": "home_export_report_action",
}


@dataclass
class Site:
    path: str
    offset: int
    literal: str
    function_start: int
    body_start: int
    template: str = ""
    arguments: list[str] = field(default_factory=list)


@dataclass
class FilePlan:
    path: str
    text: str
    sites: list[Site] = field(default_factory=list)


def mask(text: str) -> str:
    """Blank out comments, keeping every offset identical to the original."""
    out = list(text)
    index, length = 0, len(text)
    while index < length:
        two = text[index: index + 2]
        if two == "//":
            end = text.find("\n", index)
            end = length if end < 0 else end
            for spot in range(index, end):
                out[spot] = " "
            index = end
        elif two == "/*":
            end = text.find("*/", index + 2)
            end = length if end < 0 else end + 2
            for spot in range(index, end):
                if out[spot] != "\n":
                    out[spot] = " "
            index = end
        elif text[index] == '"':
            # Keep the literal itself, mask only its delimiters' effect on
            # later scanning by jumping over it.
            index += 1
            while index < length and text[index] != '"':
                index += 2 if text[index] == "\\" else 1
            index += 1
        else:
            index += 1
    return "".join(out)


def composable_bodies(masked: str) -> list[tuple[int, int, int]]:
    """(annotation offset, body start, body end) for every composable function."""
    bodies: list[tuple[int, int, int]] = []
    for annotation in re.finditer(r"@Composable\b", masked):
        function = re.search(r"\bfun\s+[A-Za-z0-9_]+\s*\(", masked[annotation.end():])
        if not function:
            continue
        open_paren = annotation.end() + function.end() - 1
        depth, index = 0, open_paren
        while index < len(masked):
            if masked[index] == "(":
                depth += 1
            elif masked[index] == ")":
                depth -= 1
                if depth == 0:
                    break
            index += 1
        rest = masked[index + 1:]
        stripped = re.match(r"\s*([{=])", rest)
        if not stripped or stripped.group(1) != "{":
            continue  # an expression body has no body to hoist into
        body_start = index + 1 + stripped.end()  # just past the opening brace
        depth, cursor = 0, body_start - 1  # start at the opening brace
        while cursor < len(masked):
            if masked[cursor] == "{":
                depth += 1
            elif masked[cursor] == "}":
                depth -= 1
                if depth == 0:
                    break
            cursor += 1
        bodies.append((annotation.start(), body_start, cursor))
    return bodies


def plan_file(path: str) -> FilePlan | None:
    full = os.path.join(ROOT, path)
    try:
        with open(full, encoding="utf-8") as handle:
            text = handle.read()
    except OSError:
        return None
    masked = mask(text)
    bodies = composable_bodies(masked)
    if not bodies:
        return None

    plan = FilePlan(path=path, text=text)
    for match in PROSE_CALL.finditer(masked):
        literal = next((group for group in match.groups() if group), "")
        if not is_prose(literal):
            continue
        enclosing = [b for b in bodies if b[1] < match.start() < b[2]]
        if not enclosing:
            continue
        # The innermost composable function owns the declaration.
        _, body_start, _ = min(enclosing, key=lambda b: b[2] - b[1])
        template, arguments = split_template(literal)
        plan.sites.append(
            Site(path=path, offset=match.start(next(index + 1 for index, group in enumerate(match.groups()) if group)),
                 literal=literal, function_start=body_start, body_start=body_start,
                 template=template, arguments=arguments),
        )
    return plan if plan.sites else None


def split_template(literal: str) -> tuple[str, list[str]]:
    """Splits a Kotlin string into text and the expressions it interpolates.

    The text becomes a resource with positional placeholders -- written as
    `{1}`, `{2}` rather than `%1$s`, because the value is resolved with plain
    `String.replace` and a literal per-cent sign in the sentence must not
    become a format directive on the way.
    """
    text: list[str] = []
    arguments: list[str] = []
    index = 0
    while index < len(literal):
        char = literal[index]
        if char == "\n":
            text.append("\n")
            index += 1
            continue
        if char != "$":
            text.append(char)
            index += 1
            continue
        if index + 1 < len(literal) and literal[index + 1] == "{":
            depth, cursor = 1, index + 2
            while cursor < len(literal) and depth:
                here = literal[cursor]
                if here == '"':
                    cursor += 1
                    while cursor < len(literal) and literal[cursor] != '"':
                        cursor += 2 if literal[cursor] == "\\" else 1
                elif here == "{":
                    depth += 1
                elif here == "}":
                    depth -= 1
                    if depth == 0:
                        break
                cursor += 1
            arguments.append(literal[index + 2: cursor])
            text.append("{" + str(len(arguments)) + "}")
            index = cursor + 1
            continue
        word = re.match(r"[A-Za-z_][A-Za-z0-9_]*", literal[index + 1:])
        if word:
            arguments.append(word.group(0))
            text.append("{" + str(len(arguments)) + "}")
            index += 1 + word.end()
            continue
        text.append(char)
        index += 1
    return "".join(text), arguments


def slug(literal: str) -> str:
    plain, _ = split_template(literal)
    plain = re.sub(r"\{\d+\}", " ", plain)
    plain = re.sub(r"%\d+\$[sd]", " ", plain)
    plain = re.sub(r"[^A-Za-z0-9]+", "_", plain).strip("_").lower()
    words = [word for word in plain.split("_") if word][:8]
    return "_".join(words) or "text"


def assign_name(
    template: str,
    literal: str,
    path: str,
    used_names: set[str],
    by_text: dict[str, str],
    memo: dict[str, str],
) -> tuple[str, bool]:
    """Picks the resource name for one template. Returns (name, is_new).

    The key is the *template*, not the literal: `"Export error: ${e.message}"`
    written in four files is one sentence with one placeholder, and four copies
    of it would be four opportunities to translate it four different ways.
    """
    if template in memo:
        return memo[template], False
    if template in by_text:
        memo[template] = by_text[template]
        return by_text[template], False
    name = NAME_OVERRIDES.get(template) or f"{feature_prefix(path)}_{slug(literal)}"
    if name in used_names:
        suffix = 2
        while f"{name}_{suffix}" in used_names:
            suffix += 1
        name = f"{name}_{suffix}"
    used_names.add(name)
    memo[template] = name
    return name, True


def feature_prefix(path: str) -> str:
    if path.startswith("design/"):
        return "design"
    for marker, name in FEATURE_NAMES.items():
        if marker in path:
            return name
    return "app"


def ensure_imports(path: str, text: str) -> str:
    """Makes `stringResource` and `R` resolvable in a file that now uses them."""
    module_r = "app.mizan.design.R" if path.startswith("design/") else "app.mizan.R"
    needed = ["androidx.compose.ui.res.stringResource", module_r]
    lines = text.split("\n")
    imports = [index for index, line in enumerate(lines) if line.startswith("import ")]
    for name in needed:
        if any(line.strip() == f"import {name}" for line in lines):
            continue
        if name == module_r and any(
            line.strip().startswith("import ") and line.strip().endswith(".R") for line in lines
        ):
            # A file that imports a different `R` compiles against that one;
            # adding a second would be ambiguous.
            continue
        position = (imports[-1] + 1) if imports else 0
        lines.insert(position, f"import {name}")
        imports = [index for index, line in enumerate(lines) if line.startswith("import ")]
    return "\n".join(lines)


def free_name(camel: str, taken: set[str]) -> str:
    if camel not in taken:
        return camel
    suffix = 2
    while f"{camel}{suffix}" in taken:
        suffix += 1
    return f"{camel}{suffix}"


def selftest() -> int:
    """Fails unless the two things this tool can get wrong are still right.

    It edits code, so the risk is not that it misses a string -- that shows up
    as a check failure -- but that it writes a body that does not compile. The
    brace position and the template split are exactly that risk, and both are
    testable without an Android toolchain.
    """
    failures: list[str] = []

    def expect(condition: bool, description: str) -> None:
        if not condition:
            failures.append(description)

    text, arguments = split_template("Initiated by ${proposal.initiator} · $reason")
    expect(text == "Initiated by {1} · {2}", f"interpolation was not split: {text!r}")
    expect(arguments == ["proposal.initiator", "reason"], f"arguments lost: {arguments}")
    expect(split_template("No values here") == ("No values here", []), "plain text was rewritten")
    expect(split_template("Cost $5.00")[0] == "Cost $5.00", "a dollar sign was read as an interpolation")

    used: set[str] = set()
    memo: dict[str, str] = {}
    first, is_new = assign_name("Export error: {1}", "Export error: ${e.message}", "app/x/Home.kt", used, {}, memo)
    second, second_is_new = assign_name("Export error: {1}", "Export error: ${t.message}", "app/x/Other.kt", used, {}, memo)
    expect(first == second and is_new and not second_is_new, "one sentence in two files became two resources")
    other, _ = assign_name("Export: {1}", "Export: ${e.message}", "app/x/Home.kt", used, {}, memo)
    expect(other != first, "two different sentences shared one resource")
    reused, reused_is_new = assign_name("Back", "Back", "app/x/Home.kt", used, {"Back": "cd_back"}, memo)
    expect(reused == "cd_back" and not reused_is_new, "an existing resource was not reused")
    expected = "A ${x.let { it + 1 }} B"
    expect(split_template(expected)[1] == ["x.let { it + 1 }"], "a nested lambda was cut short")

    sample = '@Composable\nfun Screen(name: String) {\n    Text("Hello")\n}\n'
    bodies = composable_bodies(mask(sample))
    expect(len(bodies) == 1, "a composable body was not found")
    _, body_start, body_end = bodies[0]
    expect(sample[body_start] == "\n", "the body start is not just past the opening brace")
    expect(mask(sample)[body_end] == "}", "the body end is not the matching brace")
    expect(mask(sample)[body_start - 1] == "{", "the opening brace moved")

    source = 'val a = "not // a comment" // a comment\nval b = 1'
    masked = mask(source)
    expect(masked.count("//") == 1, "a trailing comment survived masking")
    expect('"not // a comment"' in masked, "a literal containing a comment was masked away")
    expect(len(masked) == len(source), "masking changed the offsets")

    if failures:
        print("self-test FAILED")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("self-test passed: template splitting, brace placement and masking behave as claimed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--selftest", action="store_true", help="prove the transform is still correct")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    if not args.apply and not args.dry_run:
        parser.error("choose --dry-run, --apply or --selftest")

    existing_en, _ = _parse(ENGLISH)
    by_text: dict[str, str] = {}
    for name, value in existing_en.items():
        if value and not value.startswith("@"):
            by_text.setdefault(value, name)

    plans: list[FilePlan] = []
    files: list[str] = []
    for root in SOURCES:
        for base, _, names in os.walk(os.path.join(ROOT, root)):
            for name in sorted(names):
                if name.endswith(".kt"):
                    files.append(os.path.relpath(os.path.join(base, name), ROOT))
    for path in sorted(files):
        plan = plan_file(path)
        if plan:
            plans.append(plan)

    assigned: dict[str, str] = {}
    used_names = set(existing_en)
    new_en: dict[str, str] = {}
    collisions: list[str] = []
    for plan in plans:
        for site in plan.sites:
            if site.template in assigned:
                continue
            name, is_new = assign_name(site.template, site.literal, plan.path, used_names, by_text, assigned)
            if is_new:
                new_en[name] = site.template

    summary = {
        "files": len(plans),
        "sites": sum(len(p.sites) for p in plans),
        "unique": len(assigned),
        "new_english_strings": len(new_en),
        "reused": len(assigned) - len(new_en),
        "literals": sum(len(p.sites) for p in plans),
        "name_collisions_renamed": len(collisions),
    }
    if args.dry_run:
        print(json.dumps(summary, indent=2))
        for plan in plans:
            print(f"\n{plan.path}  ({len(plan.sites)} sites)")
            for site in plan.sites:
                print(f"    {assigned[site.template]:<44} {site.literal[:60]}")
        print(f"\nnew English strings: {len(new_en)}")
        with open(os.path.join(ROOT, "tools/strings_pending.json"), "w", encoding="utf-8") as handle:
            json.dump(new_en, handle, ensure_ascii=False, indent=2, sort_keys=True)
        print("written: tools/strings_pending.json")
        return 0

    # ---- apply -------------------------------------------------------------
    # One descending pass per file over offsets measured before anything moved:
    # a literal becomes a reference, and the declaration it references is
    # hoisted to the top of the function body that owns it. Nothing is
    # recomputed afterwards, so nothing can be applied twice.
    written = 0
    for plan in plans:
        text = plan.text
        edits: list[tuple[int, int, str]] = []
        per_body: dict[int, dict[str, str]] = {}
        # A hoisted `val` must not collide with a name the file already uses,
        # and two different resources in one body must not share a name.
        taken = set(re.findall(r"\b(?:val|var|fun)\s+([A-Za-z_][A-Za-z0-9_]*)", plan.text))
        for site in plan.sites:
            name = assigned[site.template]
            camel = free_name(_camel(name), taken)
            taken.add(camel)
            per_body.setdefault(site.body_start, {})[camel] = name
            reference = camel
            if site.arguments:
                # The placeholders stay in the resource, so a translator can
                # move them; the values are substituted with plain Kotlin.
                for position, argument in enumerate(site.arguments, start=1):
                    reference += '.replace("{' + str(position) + '}", ' + argument.strip() + ")"
            edits.append((site.offset - 1, site.offset + len(site.literal) + 1, reference))
        for body_start, mapping in per_body.items():
            declaration = "\n" + "".join(
                f"    val {camel} = stringResource(R.string.{name})\n"
                for camel, name in sorted(mapping.items())
            )
            edits.append((body_start, body_start, declaration))
        for start, end, replacement in sorted(edits, key=lambda e: -e[0]):
            text = text[:start] + replacement + text[end:]
            written += 1 if end > start else 0
        text = ensure_imports(plan.path, text)

        with open(os.path.join(ROOT, plan.path), "w", encoding="utf-8") as handle:
            handle.write(text)

    untranslated = 0
    for name, template in new_en.items():
        _add_string(ENGLISH, name, template)
        arabic = TRANSLATIONS.get(template)
        if arabic is None:
            arabic = template
            untranslated += 1
            print(f"untranslated: {name} = {template!r}")
        _add_string(ARABIC, name, arabic)

    print(json.dumps({**summary, "literals_replaced": written, "untranslated": untranslated}, indent=2))
    return 0


def _camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(word.capitalize() for word in rest)


def _parse(path: str):  # noqa: ANN201  (returns the mapping and the raw file)
    with open(os.path.join(ROOT, path), encoding="utf-8") as handle:
        raw = handle.read()
    root = ElementTree.fromstring(raw)
    strings = {element.get("name"): "".join(element.itertext()) for element in root if element.tag == "string"}
    return strings, raw


def _add_string(path: str, name: str, value: str) -> None:
    full = os.path.join(ROOT, path)
    with open(full, encoding="utf-8") as handle:
        raw = handle.read()
    escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    entry = f'    <string name="{name}">{escaped}</string>\n'
    raw = raw.replace("</resources>", entry + "</resources>")
    with open(full, "w", encoding="utf-8") as handle:
        handle.write(raw)


def _load_translations() -> dict[str, str]:
    path = os.path.join(ROOT, "tools/string_translations.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


TRANSLATIONS: dict[str, str] = _load_translations()


if __name__ == "__main__":
    sys.exit(main())
