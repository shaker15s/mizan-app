# Continuous integration

The workflow is a real file in this repository, at **`.github/ci/ci.yml`**, and
it is installed into the place GitHub reads with one command:

```bash
python3 tools/install_ci.py          # writes .github/workflows/ci.yml
python3 tools/install_ci.py --check  # fails when the two have drifted apart
```

## Why it is not already at `.github/workflows/ci.yml`

The automation identity that produced this tree may not push there. GitHub
refuses the whole push, whatever else it contains:

```
! [remote rejected] refusing to allow a GitHub App to create or update
workflow `.github/workflows/ci.yml` without `workflows` permission
```

That is a permission on the token, not a problem with the file. Everything
else -- the workflow content, the checks it runs, the installer, and a check
that the installed copy is current -- is committed and verifiable from a plain
checkout. One command by someone with repository rights turns it on. Until
then this file is honest about what it is: a pipeline that no runner has
executed, whose contents are nonetheless checked here by
`tools/ci_check.py`.

`tools/ci_check.py` is what keeps the workflow and this document from drifting
apart. It reads the workflow structurally -- job names, every `run:` step
including the multi-line ones -- and fails when:

* a step calls a `tools/*.py` that does not exist;
* a tool that ships a `--selftest` is invoked without one, which would mean the
  planted violations that prove the check can fail are never exercised;
* a gate command the plan requires is never invoked;
* this document lists a job the workflow does not have, or the workflow has a
  job this document does not list;
* the installable copy and the installed copy have drifted.

It found one real instance of the third kind when it was written: this document
described an `l10n` job that the inline workflow block did not contain. The
documented gate did not exist.

## What runs on every push and pull request

| job | what it runs |
| --- | --- |
| `static` | `tools/repo_check.py --no-write`, `tools/ci_check.py --strict --selftest`, `tools/install_ci.py --check`, `tools/check_contrast.py`, `tools/render_brand.py --check`, and a check that the committed Gradle wrapper is a real wrapper |
| `l10n` | `tools/strings_check.py --strict --selftest` (every `R.string` defined, both locales in step, no hardcoded text, touch targets, text floor) and `tools/extract_strings.py --selftest`, which fails if the tree has slipped back to literal text |
| `security` | `tools/security_check.py --strict` (secrets, manifest, credentials, logging, governed routes, dependency floors) and `tools/release_check.py` (flavor, signing, versioning, mapping, rollback) |
| `codeql` | CodeQL `security-and-quality` over `java-kotlin`, after compiling `:domain`, `:service`, `:integration` and `:app` so the analysis sees code and not only syntax |
| `dependencies` | `actions/dependency-review-action` on pull requests, failing at `high` |
| `jvm` | `:domain:test :integration:test :service:test`, then `tools/jvm_check.py` (the same suite with no Gradle), then `tools/syntax_check.py` over every Kotlin file, and the test reports as artifacts |
| `toolchain` | `tools/bootstrap_toolchain.py` and `tools/jvm_check.py` with no `setup-java`, no Maven and no Gradle: the fallback, proved |
| `android` | `:app:assembleDemoDebug :app:assembleStagingDebug :app:assembleProductionRelease` and lint on `:app`, `:data`, `:design` |

Two jobs exist because of what this repository could not prove in the sandbox
that wrote it. `codeql` is gate 19 of the plan, static analysis that needed a
runner and a scanner; `toolchain` is the sandbox's own limitation turned into a
job, so that "it compiles with a JDK and a compiler and nothing else" is
checked on every push rather than asserted.

The Android job needs `android-actions/setup-android` for the SDK. The release
build is unsigned unless `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, and
`KEY_PASSWORD` are set in the environment, which the build treats as optional;
`tools/release_check.py` is what fails when a signed release is asked for and
the material is missing.

Without Gradle, `python3 tools/jvm_check.py` compiles `:domain`, `:service` and
`:integration` and runs their 540 tests with a JDK and kotlinc alone, and
`python3 tools/syntax_check.py` parses every Kotlin file with the real parser.
They cover the invariants, the governed pipeline, the ERP boundary, the fuzz
and concurrency suites, and the syntax; they cannot cover the UI. The `l10n`
job is the exception that proves how much of the UI *is* checkable without a
device: strings, resources, touch targets and text sizes are all in the tree,
not on the screen. See `docs/ACCESSIBILITY.md` for what remains a device claim.

`python3 tools/bootstrap_toolchain.py` provisions that JDK and compiler on a
machine that has neither, from the only index it can reach, so the fallback
works in a locked-down environment as well as on a normal runner.

## What is still not proven

Every job here is written and reviewed; none has run on GitHub's runners from
this environment. The two that cannot be verified anywhere but a runner are
`codeql` and `android`. The honesty line stands: until a machine with the
Android SDK runs the last job, the APK, the screenshots, the macrobenchmark and
the baseline profile are claims about this repository, not results from it.
