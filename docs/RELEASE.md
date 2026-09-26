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

`Wakeel_API_BASE_URL` is a build-time field. Empty is legal and means the production app refuses writes. It must not be filled with a placeholder host.

## What a release must not contain

- The simulation authority (excluded by source set, not by a runtime flag).
- Cleartext traffic.
- A destructive migration fallback.
- Disabled shrinking. It is on. If a future release turns it off, that change needs a written reason in this file.

## Not done

There is no Play track, no baseline profile, and no assembled APK in this checkout. `gradlew` is not committed because the wrapper jar could not be fetched here.
