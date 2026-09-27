# Release

## Flavors

| Flavor | Application id | Authority | Shrinking |
| --- | --- | --- | --- |
| demo | `app.mizan.demo` | Simulator | Release minify and resource shrinking are on for every flavor |
| staging | `app.mizan.staging` | Remote, fail closed | same |
| production | `app.mizan` | Remote, fail closed | same |

`DEMO_MODE` is true only on demo. Staging and production compile `src/remote/java`, not `src/demo/java`.

Debug builds do not minify. They are not the release artifact.

## Identity break

The previous application id was `com.aistudio.mizan.erpgov`. The new id is `app.mizan`. This is not an update-in-place. No Play listing was in the repository. Firebase was unused and is gone, so the old id was not holding a backend.

## Signing

Release signing is applied only when `KEYSTORE_PATH` exists and `STORE_PASSWORD` and `KEY_PASSWORD` are set. Otherwise the release build is unsigned. CI must set those variables. A missing keystore is not replaced with a debug key.

## Service URL

`WAKEEL_API_BASE_URL` is a build-time field. Empty is legal and means the production app refuses writes. It must not be filled with a placeholder host.

The name is exact: the build reads `WAKEEL_API_BASE_URL`, and a pipeline that
sets a differently spelled variable produces a build that talks to nothing
while looking configured. `tools/release_check.py --env` refuses that.

## Versioning

`versionCode` is a literal integer in `app/build.gradle.kts` and must move
forward on every upload: Play rejects a repeated one, and a build whose version
is computed is a build nobody can name. `versionName` is semver and describes
the same upload. `tools/release_check.py` reads both from the tree, so a
release cannot be cut by accident with the previous version.

## Receipt key pinning

Staging and production pin the authority's receipt key at build time:

| variable | what it is |
| --- | --- |
| `WAKEEL_RECEIPT_KEY_ID` | the key's id, so a rotation can be named in a receipt |
| `WAKEEL_RECEIPT_PUBLIC_KEY` | the encoded Ed25519 public key the build verifies against |

They are pinned rather than fetched: a phone that downloads the key it checks
signatures against is checking them against whatever the server chose to send.
A build with a key and no id, or an id and no key, is refused by
`tools/release_check.py --env` -- half a pin is not a pin, and the app would
otherwise report a verification it cannot perform.

The demo channel pins nothing on purpose, and the app then says it did not
verify a receipt rather than showing a tick.

## Mapping and symbols

A minified release is unreadable without its R8 mapping. Every release uploads
`app/build/outputs/mapping/<flavor>Release/mapping.txt` alongside the artifact
and keeps it as long as crash reports are retained; a mapping file that is lost
turns every future stack trace into `a.a.a`. The file is git-ignored -- it is a
release artifact, not source -- and it must never be regenerated to match a
different build.

## Rollback

Two mechanisms, in this order:

1. **Server-side.** The authority is the product's truth; a bad app build can
   be neutralised by refusing its session version, by expiring approvals, or by
   turning a capability off in the policy. Nothing in this list needs a store
   review.
2. **Client-side.** Play staged rollout (5% → 25% → 100%) with a halt on
   rollout errors. A halted rollout does not pull the app back off phones that
   already have it, so the server-side lever is the one that works immediately.

A rollback is not a downgrade: `versionCode` still moves forward, and the
previous build is re-cut with a new code if it has to be reshipped.

## What a release must not contain

- The simulation authority (excluded by source set, not by a runtime flag).
- Cleartext traffic.
- A destructive migration fallback.
- Disabled shrinking. It is on. If a future release turns it off, that change needs a written reason in this file.

## What the pipeline checks before anything is built

`python3 tools/release_check.py` reads the tree: the production flavor is not a
demo build and carries no application-id suffix, the release build type does
not fall back to the debug key, minification and resource shrinking are on, the
version is a literal that moves forward, no signing artifact is committed, and
this file says what happens to the mapping file and how a rollback works.
`--env` additionally requires the variables above and refuses a non-https
authority, an unpaired receipt key, or a key that is obviously not a key.
`python3 tools/security_check.py` is the companion for the code itself.

Neither replaces a real pipeline: they check what a tree can be checked for,
and a green run here is not a signed upload.

## Not done

This checkout cannot produce a signed artifact, and says so instead of
pretending: there is no keystore here, no Play track, no baseline profile, and
no assembled APK. There is no `.github/workflows/` either -- the sandbox that
produced this tree may not write there, so the workflow lives in
`docs/CI.md` and runs these checks plus the JVM suite where it is installed.
