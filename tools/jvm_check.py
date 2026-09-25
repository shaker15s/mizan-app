#!/usr/bin/env python3
"""Compile and run the platform independent modules without Gradle.

The Android modules cannot be built everywhere: they need the Android SDK,
the Android Gradle Plugin and a few hundred artifacts from Google's Maven
repository.  Two modules are plain Kotlin on the JVM and depend on nothing
but the standard library, kotlinx.coroutines and the JDK:

    :domain    the policy engine, the money type, the audit chain
    :service   the reference authority server

This script compiles both modules -- main and test sources -- with a real
Kotlin compiler, then runs the JUnit 4 test suites on a real JVM.  It exists
so the invariants of the governed client can be verified on a machine that
has neither Gradle nor an SDK, and so a change to the policy engine cannot
silently rot.

Requirements
    java    17 or newer (JAVA_HOME, or `java` on the PATH)
    kotlinc the Kotlin compiler (KOTLINC_HOME pointing at the distribution
            root, or `kotlinc` on the PATH)

Usage
    python3 tools/jvm_check.py            # compile and run
    python3 tools/jvm_check.py --compile  # compile only

Exit code is non zero when compilation fails or any test fails.  Nothing here
is emulated: the Kotlin compiler is the real compiler and the tests really
execute.  The only substitute is a ~90 line JUnit 4 shim (annotation plus
assertions) because the JUnit jar is not vendored in this repository.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "build" / "jvm-check"

MODULES = {
    "domain": ["domain/src/main/kotlin", "domain/src/test/kotlin"],
    "service": ["service/src/main/kotlin", "service/src/test/kotlin"],
}

# A minimal JUnit 4.  The repository does not vendor the jar, and the point
# of this script is to run without a package manager.  Only the surface the
# tests actually use is provided.
JUNIT_SHIM = r'''
package org.junit

import kotlin.annotation.AnnotationRetention
import kotlin.annotation.AnnotationTarget
import kotlin.reflect.KClass

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
public annotation class Before

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
public annotation class After

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
public annotation class BeforeClass

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
public annotation class AfterClass

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
public annotation class Test(val expected: KClass<out Throwable> = None::class, val timeout: Long = 0L) {
    public class None : Throwable()
}

public fun interface ThrowingRunnable {
    @Throws(Throwable::class)
    public fun run()
}

public class AssertionErrorShim(message: String) : AssertionError(message)

public object Assert {
    @JvmStatic public fun fail(message: String = ""): Nothing = throw AssertionError(message)

    @JvmStatic public fun assertTrue(condition: Boolean) { if (!condition) fail("expected true") }

    @JvmStatic public fun assertTrue(message: String, condition: Boolean) { if (!condition) fail(message) }

    @JvmStatic public fun assertFalse(condition: Boolean) { if (condition) fail("expected false") }

    @JvmStatic public fun assertFalse(message: String, condition: Boolean) { if (condition) fail(message) }

    @JvmStatic public fun assertNull(value: Any?) { if (value != null) fail("expected null but was <$value>") }

    @JvmStatic public fun assertNull(message: String, value: Any?) { if (value != null) fail(message) }

    @JvmStatic public fun assertNotNull(value: Any?) { if (value == null) fail("expected a value but was null") }

    @JvmStatic public fun assertNotNull(message: String, value: Any?) { if (value == null) fail(message) }

    @JvmStatic public fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) fail("expected <$expected> but was <$actual>")
    }

    @JvmStatic public fun assertEquals(message: String, expected: Any?, actual: Any?) {
        if (expected != actual) fail("$message: expected <$expected> but was <$actual>")
    }

    @JvmStatic public fun assertEquals(expected: Long, actual: Long) {
        if (expected != actual) fail("expected <$expected> but was <$actual>")
    }

    @JvmStatic public fun assertEquals(message: String, expected: Long, actual: Long) {
        if (expected != actual) fail("$message: expected <$expected> but was <$actual>")
    }

    @JvmStatic public fun assertEquals(expected: Double, actual: Double, delta: Double) {
        if (kotlin.math.abs(expected - actual) > delta) fail("expected <$expected> but was <$actual>")
    }

    @JvmStatic public fun assertNotEquals(unexpected: Any?, actual: Any?) {
        if (unexpected == actual) fail("did not expect <$actual>")
    }

    @JvmStatic public fun assertNotEquals(message: String, unexpected: Any?, actual: Any?) {
        if (unexpected == actual) fail(message)
    }

    @JvmStatic public fun <T : Throwable> assertThrows(expected: Class<T>, body: ThrowingRunnable): T {
        return try {
            body.run()
            fail("expected ${expected.name} but nothing was thrown")
        } catch (thrown: Throwable) {
            if (expected.isInstance(thrown)) {
                @Suppress("UNCHECKED_CAST")
                thrown as T
            } else {
                fail("expected ${expected.name} but was ${thrown.javaClass.name}: ${thrown.message}")
            }
        }
    }

    @JvmStatic public fun <T : Throwable> assertThrows(message: String, expected: Class<T>, body: ThrowingRunnable): T {
        return try {
            body.run()
            fail("$message: expected ${expected.name} but nothing was thrown")
        } catch (thrown: Throwable) {
            if (expected.isInstance(thrown)) {
                @Suppress("UNCHECKED_CAST")
                thrown as T
            } else {
                fail("$message: expected ${expected.name} but was ${thrown.javaClass.name}")
            }
        }
    }
}
'''

RUNNER = r'''
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.net.URL
import kotlin.system.exitProcess

/**
 * Runs every compiled class that carries JUnit annotations.
 *
 * Deliberately tiny: no runner framework, no reflection library. It walks the
 * output directory, loads each class, calls @BeforeClass once, then @Before,
 * @Test and @After per instance, and reports each outcome with a duration.
 */
fun main(args: Array<String>) {
    val root = File(args[0]).absoluteFile
    val urls = arrayOf(URL("file://" + root.absolutePath + "/"))
    val loader = URLClassLoader(urls, ClassLoader.getSystemClassLoader())

    val names = mutableListOf<String>()
    fun walk(dir: File) {
        for (file in dir.listFiles() ?: emptyArray()) {
            if (file.isDirectory) walk(file)
            else if (file.name.endsWith(".class") && !file.name.contains("$")) {
                names += file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
            }
        }
    }
    walk(root)

    var passed = 0
    val failures = mutableListOf<String>()

    for (name in names.sorted()) {
        val clazz = try {
            Class.forName(name, false, loader)
        } catch (ignored: Throwable) {
            continue
        }
        val tests = (clazz.declaredMethods + clazz.methods)
            .filter { it.isAnnotationPresent(Test::class.java) }
            .toSet()
        if (tests.isEmpty()) continue

        fun callStatic(annotation: Class<out Annotation>) {
            for (method in clazz.declaredMethods) {
                if (method.isAnnotationPresent(annotation)) {
                    method.isAccessible = true
                    method.invoke(null)
                }
            }
        }

        try {
            callStatic(BeforeClass::class.java)
            for (method in tests.sortedBy { it.name }) {
                method.isAccessible = true
                val instance = if (method.modifiers and java.lang.reflect.Modifier.STATIC != 0) {
                    null
                } else {
                    clazz.getDeclaredConstructor().newInstance()
                }
                val started = System.nanoTime()
                try {
                    for (before in clazz.declaredMethods.filter { it.isAnnotationPresent(Before::class.java) }) {
                        before.isAccessible = true
                        before.invoke(instance)
                    }
                    method.invoke(instance)
                    for (after in clazz.declaredMethods.filter { it.isAnnotationPresent(After::class.java) }) {
                        after.isAccessible = true
                        after.invoke(instance)
                    }
                    val millis = (System.nanoTime() - started) / 1_000_000
                    println("  PASS  ${clazz.simpleName}.${method.name}  (${millis} ms)")
                    passed++
                } catch (failure: Throwable) {
                    val cause = failure.cause ?: failure
                    val millis = (System.nanoTime() - started) / 1_000_000
                    println("  FAIL  ${clazz.simpleName}.${method.name}  (${millis} ms)")
                    println("        ${cause.javaClass.simpleName}: ${cause.message}")
                    failures += "${clazz.simpleName}.${method.name}: ${cause.message}"
                }
            }
            callStatic(AfterClass::class.java)
        } catch (failure: Throwable) {
            val cause = failure.cause ?: failure
            println("  FAIL  ${clazz.simpleName} (class level)")
            println("        ${cause.javaClass.simpleName}: ${cause.message}")
            failures += "${clazz.simpleName}: ${cause.message}"
        }
    }

    println()
    println("tests run: ${passed + failures.size}, passed: $passed, failed: ${failures.size}")
    if (failures.isNotEmpty()) {
        for (failure in failures) println("  - $failure")
        exitProcess(1)
    }
}
'''


def resolve_java() -> Path | None:
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / "java"
        if candidate.exists():
            return candidate
    found = shutil.which("java")
    if found:
        return Path(found)
    try:  # jdk4py, when the sandbox provides the JDK as a wheel
        import jdk4py  # type: ignore

        return Path(jdk4py.JAVA)
    except Exception:
        return None


def resolve_kotlinc() -> Path | None:
    home = os.environ.get("KOTLINC_HOME")
    if home:
        candidate = Path(home) / "bin" / "kotlinc"
        if candidate.exists():
            return candidate
        candidate = Path(home) / "bin" / "kotlinc-jvm"
        if candidate.exists():
            return candidate
    for name in ("kotlinc", "kotlinc-jvm"):
        found = shutil.which(name)
        if found:
            return Path(found)
    return None


def kotlinc_libs(kotlinc: Path) -> Path:
    lib = kotlinc.resolve().parent.parent / "lib"
    if not lib.is_dir():
        raise SystemExit(f"cannot find the compiler libraries next to {kotlinc}")
    return lib


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, **kwargs)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--compile", action="store_true", help="compile only, do not run the tests")
    args = parser.parse_args()

    java = resolve_java()
    kotlinc = resolve_kotlinc()
    if java is None or kotlinc is None:
        print("jvm_check: needs a JDK and the Kotlin compiler.")
        print(f"  java    : {java or 'NOT FOUND (set JAVA_HOME)'}")
        print(f"  kotlinc : {kotlinc or 'NOT FOUND (set KOTLINC_HOME to the distribution root)'}")
        print("  With Gradle installed, `./gradlew :domain:test :service:test` does the same thing.")
        return 2

    print(f"java    : {java}")
    print(f"kotlinc : {kotlinc}")
    version = run([str(java), "-version"])
    print("         " + (version.stderr.strip().splitlines() or [""])[0])

    lib = kotlinc_libs(kotlinc)
    classpath = []
    for name in ("kotlin-stdlib.jar", "kotlinx-coroutines-core-jvm.jar", "kotlin-reflect.jar"):
        jar = lib / name
        if jar.exists():
            classpath.append(str(jar))
    if not classpath:
        raise SystemExit(f"no standard library found in {lib}")

    BUILD.mkdir(parents=True, exist_ok=True)
    shim_dir = BUILD / "shim"
    shim_dir.mkdir(parents=True, exist_ok=True)
    (shim_dir / "junit_shim.kt").write_text(JUNIT_SHIM, encoding="utf-8")
    print("\n[1/4] compiling the JUnit shim")
    shim_out = BUILD / "shim-classes"
    shutil.rmtree(shim_out, ignore_errors=True)
    compile_shim = [
        str(kotlinc),
        "-nowarn",
        "-jvm-target",
        "17",
        "-cp",
        os.pathsep.join(classpath),
        "-d",
        str(shim_out),
        str(shim_dir / "junit_shim.kt"),
    ]
    result = run(compile_shim)
    if result.returncode != 0:
        print(result.stdout)
        print(result.stderr)
        return 1
    print("      ok")

    print("\n[2/4] compiling :domain and :service (main + test)")
    out = BUILD / "classes"
    shutil.rmtree(out, ignore_errors=True)
    sources: list[str] = []
    for roots in MODULES.values():
        for root in roots:
            for path in sorted((ROOT / root).rglob("*.kt")):
                sources.append(str(path))
    print(f"      {len(sources)} source files")
    compile_main = [
        str(kotlinc),
        "-jvm-target",
        "17",
        "-cp",
        os.pathsep.join(classpath + [str(shim_out)]),
        "-d",
        str(out),
        *sources,
    ]
    result = run(compile_main)
    diagnostics = result.stdout + result.stderr
    if result.returncode != 0 and ": error: " not in diagnostics:
        print(diagnostics)
        print(f"      the compiler exited with {result.returncode} without producing class files")
        return 1
    warnings = [line for line in diagnostics.splitlines() if ": warning: " in line]
    errors = [line for line in diagnostics.splitlines() if ": error: " in line]
    if errors:
        print(diagnostics)
        print(f"      {len(errors)} error(s)")
        return 1
    if warnings:
        print(f"      {len(warnings)} warning(s)")
        for line in warnings[:10]:
            print("      " + line)
    print("      ok, no errors")

    if args.compile:
        return 0

    print("\n[3/4] compiling the test runner")
    runner_src = BUILD / "runner.kt"
    runner_src.write_text(RUNNER, encoding="utf-8")
    runner_out = BUILD / "runner-classes"
    shutil.rmtree(runner_out, ignore_errors=True)
    result = run(
        [
            str(kotlinc),
            "-nowarn",
            "-jvm-target",
            "17",
            "-cp",
            os.pathsep.join(classpath + [str(shim_out), str(out)]),
            "-d",
            str(runner_out),
            str(runner_src),
        ]
    )
    if result.returncode != 0:
        print(result.stdout)
        print(result.stderr)
        return 1
    print("      ok")

    print("\n[4/4] running the suites")
    result = run(
        [
            str(java),
            "-cp",
            os.pathsep.join(classpath + [str(shim_out), str(out), str(runner_out)]),
            "RunnerKt",
            str(out),
        ]
    )
    print(result.stdout)
    if result.stderr.strip():
        print(result.stderr)
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
