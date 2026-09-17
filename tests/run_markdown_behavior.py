"""Run real Kotlin/Compose source transformation checks with pre-fetched jars.

No Gradle/build-file changes or mocks. Requires Java and a directory containing
Kotlin compiler-embeddable/stdlib/script-runtime/reflect/daemon (2.0.21 compiler),
trove4j, kotlinx-coroutines-core-jvm, JetBrains annotations, CommonMark 0.30.0,
and classes.jar from Compose 1.7.3 ui-text/ui-graphics/ui-unit/ui-util/ui-geometry,
runtime and runtime-saveable Android AARs. Jar filenames can be arbitrary.

Usage: python3 tests/run_markdown_behavior.py /absolute/path/to/jars
This complements source-contract checks; it does NOT render Android UI or prove
IME, selection, device pinch gestures, preview composition or APK build health.
"""
import pathlib
import subprocess
import sys
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
jar_dir = pathlib.Path(sys.argv[1]).resolve()
jars = sorted(jar_dir.glob('*.jar'))
if not jars:
    raise SystemExit('No harness jars found')
classpath = ':'.join(str(jar) for jar in jars)
with tempfile.TemporaryDirectory(prefix='markdown-tests-') as temporary:
    output = str(pathlib.Path(temporary) / 'tests.jar')
    subprocess.run([
        'java', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath, '-jvm-target', '17',
        '-d', output,
        str(root / 'app/src/main/java/com/asoraksh/hermeseditor/MarkdownSourceStyling.kt'),
        str(root / 'tests/MarkdownSourceStylingTest.kt'),
    ], check=True)
    subprocess.run([
        'java', '-cp', output + ':' + classpath,
        'com.asoraksh.hermeseditor.MarkdownSourceStylingTestKt',
    ], check=True)
