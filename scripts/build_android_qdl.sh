#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SRC_DIR="$ROOT_DIR/third_party/qdl"
BUNDLE_DIR="$ROOT_DIR/app/src/main/assets/edl_bundle"
BUILD_DIR="$ROOT_DIR/build/android-qdl"
GENERATED_DIR="$BUILD_DIR/generated"
OUTPUT="$BUILD_DIR/qdl"
API_LEVEL="${API_LEVEL:-26}"
INSTALL=0

usage() {
    cat <<'EOF'
用法:
  scripts/build_android_qdl.sh [--install] [--output PATH]

说明:
  从 third_party/qdl 源码构建 Android aarch64 版 qdl。
  依赖 app/src/main/assets/edl_bundle 中现成的 libxml2/libusb 头文件和动态库。

参数:
  --install       构建完成后覆盖到 app/src/main/assets/edl_bundle/bin/qdl
  --output PATH   指定输出二进制路径

环境变量:
  NDK             Android NDK 根目录，未指定时自动探测
  API_LEVEL       Android API 级别，默认 26
  QDL_VERSION     写入 version.h 的版本字符串
EOF
}

while (($# > 0)); do
    case "$1" in
        --install)
            INSTALL=1
            shift
            ;;
        --output)
            OUTPUT="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "未知参数: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

detect_ndk() {
    if [[ -n "${NDK:-}" && -d "$NDK" ]]; then
        printf '%s\n' "$NDK"
        return 0
    fi

    local candidates=(
        "/root/my/.ndk/android-ndk-r29"
        "/root/my/.ndk/android-ndk-r26d"
        "/root/tooling/android-sdk/ndk/21.4.7075529"
    )
    local path
    for path in "${candidates[@]}"; do
        if [[ -d "$path" ]]; then
            printf '%s\n' "$path"
            return 0
        fi
    done
    return 1
}

NDK_DIR="$(detect_ndk)" || {
    echo "未找到 Android NDK，请设置 NDK 环境变量" >&2
    exit 1
}

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TOOLCHAIN/aarch64-linux-android${API_LEVEL}-clang"
STRIP="$TOOLCHAIN/llvm-strip"

if [[ ! -x "$CC" ]]; then
    echo "未找到 clang: $CC" >&2
    exit 1
fi

mkdir -p "$BUILD_DIR" "$GENERATED_DIR"

VERSION_VALUE="${QDL_VERSION:-android-source-$(date -u +%Y%m%d-%H%M%S)}"
printf '#define VERSION "%s"\n' "$VERSION_VALUE" > "$GENERATED_DIR/version.h"
printf '#define VERSION "%s"\n' "$VERSION_VALUE" > "$SRC_DIR/.version.h"
cp "$GENERATED_DIR/version.h" "$SRC_DIR/version.h"

SRCS=(
    firehose.c
    io.c
    qdl.c
    sahara.c
    util.c
    patch.c
    program.c
    read.c
    sha2.c
    sim.c
    ufs.c
    usb.c
    ux.c
    oscompat.c
    vip.c
    sparse.c
    gpt.c
    oplus_vip.c
    oplus_token.c
    aes256.c
    pathbuf.c
)

COMMON_FLAGS=(
    -O2
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-sign-compare
    -fPIE
    -D_FILE_OFFSET_BITS=64
    -DBYTE_ORDER=__BYTE_ORDER__
    -DLITTLE_ENDIAN=__ORDER_LITTLE_ENDIAN__
    -DBIG_ENDIAN=__ORDER_BIG_ENDIAN__
    -I"$SRC_DIR"
    -I"$GENERATED_DIR"
    -I"$BUNDLE_DIR/include"
    -I"$BUNDLE_DIR/include/libxml2"
    -I"$BUNDLE_DIR/include/libusb-1.0"
    -DANDROID_BUILD=1
)

OBJS=()
for src in "${SRCS[@]}"; do
    obj="$BUILD_DIR/${src%.c}.o"
    "$CC" "${COMMON_FLAGS[@]}" -c "$SRC_DIR/$src" -o "$obj"
    OBJS+=("$obj")
done

mkdir -p "$(dirname "$OUTPUT")"
"$CC" -pie -o "$OUTPUT" \
    "${OBJS[@]}" \
    -L"$BUNDLE_DIR/lib" \
    -lxml2 -lusb-1.0 -lz -lm -ldl

if [[ -x "$STRIP" ]]; then
    "$STRIP" "$OUTPUT" || true
fi

if [[ "$INSTALL" -eq 1 ]]; then
    cp "$OUTPUT" "$BUNDLE_DIR/bin/qdl"
    chmod 755 "$BUNDLE_DIR/bin/qdl"
fi

echo "qdl 已构建: $OUTPUT"
