#!/usr/bin/env bash
set -e
SDK=/c/Users/STAR/.local/android-sdk
BT=$SDK/build-tools/36.0.0
AJ=$SDK/platforms/android-36/android.jar
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes build/gen
echo "== javac"
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -cp "$(cygpath -w $AJ);$(cygpath -w libs/api-82.jar)" \
  -d build/classes $(find src -name '*.java')
echo "== jar"
jar cf build/classes.jar -C build/classes .
echo "== d8"
"$BT/d8" --lib "$(cygpath -w $AJ)" --min-api 24 --output build build/classes.jar
echo "== aapt2 compile"
"$BT/aapt2" compile --dir res -o build/res.zip
echo "== aapt2 link"
"$BT/aapt2" link -o build/base.apk -I "$(cygpath -w $AJ)" \
  --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 24 --target-sdk-version 36 build/res.zip
echo "== pack dex"
"/c/Users/STAR/.local/bin/python3.exe" - <<'PY'
import zipfile, shutil, os
shutil.copy('build/base.apk', 'build/unsigned.apk')
z = zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED)
z.write('build/classes.dex', 'classes.dex')
z.close()
print('packed', os.path.getsize('build/unsigned.apk'))
PY
echo "== keystore"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android \
  -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=UltraOne, O=Star, C=CN" >/dev/null 2>&1
echo "== zipalign + sign"
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out ultraone.apk build/aligned.apk
ls -l ultraone.apk
"$BT/apksigner" verify --print-certs ultraone.apk | head -5
