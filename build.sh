#!/usr/bin/env bash
# 口袋守护 · 无 Gradle 构建脚本（aapt2 + javac + d8 + zipalign + apksigner）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TC="${TC:-$ROOT/../.toolchain}"
JDK="${JDK:-$TC/jdk}"
BT="${BT:-$TC/bt_tmp/android-14}"
ANDROID_JAR="${ANDROID_JAR:-$TC/sdk_tmp/android-34/android.jar}"
PY="${PY:-$(command -v python || command -v python3 || true)}"

# Git Bash 路径 -> Windows 路径（外部 exe 只认后者）
w() { cygpath -w "$1"; }

BIN="$ROOT/build"
OUT="$ROOT/PocketGuard.apk"

echo "==> 清理"
rm -rf "$BIN"
mkdir -p "$BIN/res" "$BIN/gen" "$BIN/classes" "$BIN/dex"

echo "==> 编译资源"
"$BT/aapt2.exe" compile --dir "$(w "$ROOT/res")" -o "$(w "$BIN/res/resources.zip")"

echo "==> 链接资源"
"$BT/aapt2.exe" link \
    -o "$(w "$BIN/base.apk")" \
    -I "$(w "$ANDROID_JAR")" \
    --manifest "$(w "$ROOT/AndroidManifest.xml")" \
    -R "$(w "$BIN/res/resources.zip")" \
    --java "$(w "$BIN/gen")" \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    --version-code 3 \
    --version-name 1.2 \
    --auto-add-overlay

echo "==> 编译 Java"
: > "$BIN/sources.txt"
while IFS= read -r f; do w "$f" >> "$BIN/sources.txt"; done < <(find "$ROOT/src" -name '*.java')
while IFS= read -r f; do w "$f" >> "$BIN/sources.txt"; done < <(find "$BIN/gen" -name '*.java')
"$JDK/bin/javac.exe" -encoding UTF-8 -source 11 -target 11 -nowarn \
    -classpath "$(w "$ANDROID_JAR")" -d "$(w "$BIN/classes")" "@$(w "$BIN/sources.txt")"

echo "==> dex"
CLASSES=$(find "$BIN/classes" -name '*.class' | while IFS= read -r f; do w "$f"; done)
"$JDK/bin/java.exe" -cp "$(w "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
    --lib "$(w "$ANDROID_JAR")" --min-api 26 --output "$(w "$BIN/dex")" $CLASSES

echo "==> 打包"
"$PY" - "$(w "$BIN/base.apk")" "$(w "$BIN/dex/classes.dex")" "$(w "$BIN/unsigned.apk")" <<'PY'
import sys, zipfile
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(src, 'r')
zout = zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    zout.writestr(item, zin.read(item.filename))
zout.write(dex, 'classes.dex')
zout.close()
zin.close()
print('packed ->', dst)
PY

echo "==> zipalign"
"$BT/zipalign.exe" -f -p 4 "$(w "$BIN/unsigned.apk")" "$(w "$BIN/aligned.apk")"

echo "==> 签名"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
    "$JDK/bin/keytool.exe" -genkeypair -v -keystore "$(w "$KS")" -storepass android \
        -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
        -validity 10950 -dname "CN=PocketGuard, OU=Dev, O=PocketGuard, L=NA, ST=NA, C=CN" >/dev/null 2>&1
fi
rm -f "$OUT"
"$JDK/bin/java.exe" -jar "$(w "$BT/lib/apksigner.jar")" sign \
    --ks "$(w "$KS")" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$(w "$OUT")" "$(w "$BIN/aligned.apk")"

echo "==> 校验"
"$JDK/bin/java.exe" -jar "$(w "$BT/lib/apksigner.jar")" verify --print-certs "$(w "$OUT")" | head -6
ls -lh "$OUT"
echo "完成：$OUT"
