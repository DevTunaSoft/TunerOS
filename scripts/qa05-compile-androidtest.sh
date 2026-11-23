#!/usr/bin/env bash
# QA-05 local compilation attempt for the androidTest sources. No emulator required.
# Uses a locally cached Kotlin compiler; when no Android SDK platform is installed it
# type-checks the instrumentation against minimal framework stubs and says so explicitly.
# A stub compile is NOT an APK build and NOT a device PASS.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
else
  echo "QA05 BLOCKED: no JDK found; set JAVA_HOME to a JDK 17 and rerun"
  exit 3
fi

COMPILER=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin" \
  -type f -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | sort | tail -1 || true)
if [ -z "$COMPILER" ]; then
  echo "QA05 BLOCKED: no cached Kotlin compiler jar; cannot compile androidTest sources here"
  exit 3
fi
VERSION=$(basename "$COMPILER" | sed -E 's/kotlin-compiler-embeddable-(.*)\.jar/\1/')
CP=""
for jar in $(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin" \
                  "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx" \
                  "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.deps" \
                  "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations" \
                  -type f -name '*.jar' 2>/dev/null); do
  # Native Windows tools do not understand MSYS /c/... paths.
  native=$(cygpath -m "$jar" 2>/dev/null || printf '%s' "$jar")
  CP="$CP$native;"
done
STDLIB_RAW=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" \
  -type f -name "kotlin-stdlib-$VERSION.jar" 2>/dev/null | head -1 || true)
if [ -z "$STDLIB_RAW" ]; then
  echo "QA05 BLOCKED: no kotlin-stdlib-$VERSION.jar; cannot run the compiled assertions"
  exit 3
fi
STDLIB=$(cygpath -m "$STDLIB_RAW" 2>/dev/null || printf '%s' "$STDLIB_RAW")
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *) SEP=':' ;;
esac

BUILD="scripts/qa05/.qa05-build"
TMP="scripts/qa05/.qa05-stubs"
trap 'rm -rf "$BUILD" "$TMP"' EXIT
mkdir -p "$BUILD" "$TMP/src/android/app" "$TMP/src/android/os" "$TMP/src/android/util"
ASSERTIONS="app/src/androidTest/java/com/devtunasoft/tuneros/qa05/Qa05GuideAssertions.kt"
INSTRUMENTATION="app/src/androidTest/java/com/devtunasoft/tuneros/qa05/Qa05GuideInstrumentation.kt"

echo "QA05 compiler: Kotlin $VERSION on $($JAVA_BIN -version 2>&1 | head -1)"
compile() {
  local out="$1"; shift
  $JAVA_BIN -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$STDLIB" -d "$out" "$@"
}

compile "$BUILD/assertions" "$ASSERTIONS"
echo "QA05 KOTLIN COMPILE PASS: $ASSERTIONS"

ANDROID_JAR=""
for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/AppData/Local/Android/Sdk" "$HOME/Android/Sdk"; do
  if [ -n "$root" ] && [ -d "$root/platforms" ]; then
    candidate=$(find "$root/platforms" -maxdepth 2 -type f -name android.jar 2>/dev/null | sort | tail -1 || true)
    if [ -n "$candidate" ]; then ANDROID_JAR=$(cygpath -m "$candidate" 2>/dev/null || printf '%s' "$candidate"); break; fi
  fi
done

if [ -n "$ANDROID_JAR" ]; then
  $JAVA_BIN -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$STDLIB$SEP$ANDROID_JAR" \
    -d "$BUILD/instrumentation" "$ASSERTIONS" "$INSTRUMENTATION"
  echo "QA05 INSTRUMENTATION COMPILE PASS: compiled against $ANDROID_JAR (not a device run)"
else
  echo "QA05 DEVICE TOOLCHAIN BLOCKED: no Android SDK platform android.jar found"
  echo "QA05 attempting stub type-check of the instrumentation against the framework API it uses"
  cat > "$TMP/src/android/os/Bundle.kt" <<'EOF'
package android.os
class Bundle {
    fun putInt(key: String, value: Int): Bundle = this
    fun putString(key: String, value: String?): Bundle = this
    fun getString(key: String): String? = null
}
EOF
  cat > "$TMP/src/android/app/Activity.kt" <<'EOF'
package android.app
open class Activity {
    companion object {
        const val RESULT_OK = -1
        const val RESULT_CANCELED = 0
    }
}
EOF
  cat > "$TMP/src/android/app/Instrumentation.kt" <<'EOF'
package android.app
abstract class Instrumentation {
    open fun onRun(arguments: android.os.Bundle) {}
    fun finish(resultCode: Int, resultData: android.os.Bundle) {}
}
EOF
  cat > "$TMP/src/android/util/Log.kt" <<'EOF'
package android.util
object Log {
    fun i(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String): Int = 0
}
EOF
  compile "$BUILD/instrumentation" "$ASSERTIONS" "$INSTRUMENTATION" \
    "$TMP/src/android/os/Bundle.kt" "$TMP/src/android/app/Activity.kt" \
    "$TMP/src/android/app/Instrumentation.kt" "$TMP/src/android/util/Log.kt"
  echo "QA05 INSTRUMENTATION STUB COMPILE PASS: syntax and types checked; NOT an APK build or device PASS"
fi

$JAVA_BIN -cp "$BUILD/assertions$SEP$STDLIB" com.devtunasoft.tuneros.qa05.Qa05GuideAssertions
