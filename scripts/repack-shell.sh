#!/usr/bin/env bash
# 一键 dpt-shell 整壳：对已构建的 release APK 做 DEX 方法体抽取加密，再用 debug.keystore 重签。
#   重签是硬约束：native 签名校验绑定 debug 证书(fac61745...)，换证书会被判篡改→闪退。
# 用法: bash scripts/repack-shell.sh
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DPT_DIR="${DPT_DIR:-/root/dpt-shell}"
SDK="${ANDROID_SDK_ROOT:-/root/tooling/android-sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

IN_APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
RULES="$ROOT/scripts/dpt-exclude.rules"
KS="$ROOT/android/app/debug.keystore"
OUT_DIR="$ROOT/dist"
TMP="$(mktemp -d)"

APKSIGNER="$(find "$SDK/build-tools" -name apksigner | sort -V | tail -1)"

[ -f "$IN_APK" ] || { echo "缺少 release APK，请先 ./gradlew :app:assembleRelease"; exit 1; }
[ -f "$DPT_DIR/executable/dpt.jar" ] || { echo "缺少 dpt.jar，请先在 $DPT_DIR 构建(见 HARDENING.md)"; exit 1; }

echo "[1/3] dpt 打壳(方法体抽取加密)..."
java -jar "$DPT_DIR/executable/dpt.jar" -f "$IN_APK" -x -r "$RULES" -o "$TMP"

PACKED="$(ls "$TMP"/*_signed.apk "$TMP"/*_unsign.apk 2>/dev/null | head -1)"
[ -f "$PACKED" ] || { echo "dpt 未产出 APK"; exit 1; }

echo "[2/3] 用 debug.keystore 重签(证书须仍为 fac61745...)..."
mkdir -p "$OUT_DIR"
cp "$PACKED" "$OUT_DIR/edlflash-shelled.apk"
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android \
    --ks-key-alias androiddebugkey --key-pass pass:android \
    "$OUT_DIR/edlflash-shelled.apk"

echo "[3/3] 校验证书指纹..."
"$APKSIGNER" verify --print-certs "$OUT_DIR/edlflash-shelled.apk" | grep -i "SHA-256" | head -1
rm -rf "$TMP"
echo "完成: $OUT_DIR/edlflash-shelled.apk —— 请在真机安装并验证可正常启动/登录/刷机。"
