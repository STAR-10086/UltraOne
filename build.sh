#!/usr/bin/env bash
# UltraOne 构建脚本（Windows / Git Bash + Android SDK build-tools）
# 用法: bash build.sh
set -e

SDK=${ANDROID_SDK:-/c/Users/STAR/.local/android-sdk}
BT="$SDK/build-tools/36.0.0"
AJ="$SDK/platforms/android-36/android.jar"
cd "$(dirname "$0")"

[ -f "$BT/d8.exe" ] || [ -f "$BT/d8" ] || { echo "找不到 d8，检查 SDK 路径: $BT"; exit 1; }
[ -f assets/billing.dex ] || { echo "缺少 assets/billing.dex（Play Billing 库 dex）"; exit 1; }

rm -rf build
mkdir -p build/classes build/gen

echo "== javac"
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -cp "$(cygpath -w "$AJ");$(cygpath -w libs/api-82.jar)" \
  -d build/classes $(find src -name '*.java')

echo "== dex"
jar cf build/classes.jar -C build/classes .
"$BT/d8" --lib "$(cygpath -w "$AJ")" --min-api 24 --output build build/classes.jar

echo "== aapt2"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$(cygpath -w "$AJ")" \
  --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 24 --target-sdk-version 36 \
  --java build/gen build/res.zip

echo "== pack"
PYBIN=${PYBIN:-/c/Users/STAR/.local/bin/python3.exe}
[ -x "$PYBIN" ] || PYBIN=python3
"$PYBIN" - <<'PY'
import zipfile, shutil, os
shutil.copy('build/base.apk', 'build/unsigned.apk')
z = zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED)
z.write('build/classes.dex', 'classes.dex')
z.close()
print('packed', os.path.getsize('build/unsigned.apk'))
PY

echo "== keystore"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=UltraOne, O=Star, C=CN" >/dev/null 2>&1
fi

echo "== sign"
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out ultraone.apk build/aligned.apk
"$BT/apksigner" verify --print-certs ultraone.apk | head -4
ls -l ultraone.apk
