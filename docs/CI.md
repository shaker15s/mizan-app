# Continuous integration

The sandbox token that produced this tree is not allowed to push files under
`.github/workflows/`, so the workflow is stored here instead of installed.

To enable it, copy the block below to `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
  workflow_dispatch:

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

# Least privilege. Jobs ask for more only where they need it.
permissions:
  contents: read

jobs:
  static:
    name: Static checks
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
      - name: Repository health check
        run: python3 tools/repo_check.py --no-write
      - name: Every preset meets WCAG AA
        run: python3 tools/check_contrast.py
      - name: Launcher icons are present and correctly sized
        run: python3 tools/render_brand.py --check
      - name: Gradle wrapper is a real wrapper
        run: |
          test -f gradle/wrapper/gradle-wrapper.jar
          unzip -l gradle/wrapper/gradle-wrapper.jar | grep -q org/gradle/wrapper/GradleWrapperMain.class
          grep -q '^distributionUrl=https\\://services.gradle.org/' gradle/wrapper/gradle-wrapper.properties

  jvm:
    name: JVM modules
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v4
      - name: Domain, integration and service tests
        run: ./gradlew :domain:test :integration:test :service:test --stacktrace
      - name: Reference service smoke test
        run: ./gradlew :service:test --tests '*MizanServiceHttpTest*' --stacktrace
      - name: The same suite without Gradle
        # The plan's portability check: the JVM modules must be provable with a
        # JDK and a Kotlin compiler alone, so a machine that cannot resolve
        # Maven Central is not a machine that cannot verify this repository.
        run: python3 tools/jvm_check.py
      - name: The same suite with a self-provisioned toolchain
        # No setup-java, no network to Maven: the bootstrap fetches a JDK and a
        # Kotlin compiler, unpacks them, and jvm_check runs against them.
        run: |
          python3 tools/bootstrap_toolchain.py --check || python3 tools/bootstrap_toolchain.py
          JAVA_HOME="$MIZAN_TOOLCHAIN_DIR/jdk/jdk4py/java-runtime"             KOTLINC_HOME="$MIZAN_TOOLCHAIN_DIR/kotlinc" python3 tools/jvm_check.py
      - if: always()
        uses: actions/upload-artifact@v4
        with:
          name: jvm-test-results
          path: |
            domain/build/reports/tests
            integration/build/reports/tests
            service/build/reports/tests
          if-no-files-found: ignore

  android:
    name: Android build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
      - name: Assemble the demo and production channels
        run: |
          ./gradlew :app:assembleDemoDebug :app:assembleStagingDebug :app:assembleProductionRelease --stacktrace
      - name: Lint
        run: ./gradlew :app:lintDemoDebug :data:lintDebug :design:lintDebug --stacktrace
      - if: always()
        uses: actions/upload-artifact@v4
        with:
          name: android-build-outputs
          path: |
            app/build/outputs/apk
            app/build/reports/lint-results-demoDebug.html
          if-no-files-found: ignore
```

What it does on every push and pull request:

| job | what it runs |
| --- | --- |
| `static` | `tools/repo_check.py --no-write`, `tools/check_contrast.py`, `tools/render_brand.py --check`, and a check that the committed wrapper is a real wrapper |
| `jvm` | `:domain:test :integration:test :service:test`, then the service HTTP suite, and uploads the reports |
| `android` | `:app:assembleDemoDebug :app:assembleStagingDebug :app:assembleProductionRelease` and lint on `:app`, `:data`, `:design` |

The Android job needs `android-actions/setup-android` for the SDK. The release
build is unsigned unless `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, and
`KEY_PASSWORD` are set in the environment, which the build treats as optional.

Without Gradle, `python3 tools/jvm_check.py` compiles `:domain`, `:service` and
`:integration` and runs their 414 tests with a JDK and kotlinc alone, and
`python3 tools/syntax_check.py` parses every Kotlin file with the real parser.
They cover the invariants, the governed pipeline, the ERP boundary and the
syntax; they cannot cover the UI.

`python3 tools/bootstrap_toolchain.py` provisions that JDK and compiler on a
machine that has neither, from the only index it can reach, so the fallback
works in a locked-down environment as well as on a normal runner.

Until a machine with a JDK runs this, CI is a plan, not a green check.
