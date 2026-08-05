#!/usr/bin/env bash
# Build librdp_transport.so for the 3 Android ABIs this project ships
# (arm64-v8a, armeabi-v7a, x86_64 — see rust-toolchain.toml's `targets`)
# into jniLibs/. Called by both build.gradle.kts's buildRdpNative task and
# tools/build-android.sh, so the build-host logic lives in exactly one place.
#
# On x86_64 hosts (e.g. GitHub Actions' ubuntu-latest runners): uses
# `cargo ndk` exactly as before — unaffected, still the default path.
#
# On aarch64 hosts (e.g. self-hosted ARM runners/dev machines): NDK only
# ships an x86_64 Linux host build of clang (Google doesn't publish a
# linux-aarch64 one), so `cargo ndk` would need qemu-user to run it — and
# qemu-user cannot execute rustc's own multi-threaded startup path reliably
# (segfaults in realloc/thread-naming; confirmed identically across qemu
# 8.2.2 and a from-source 11.0.3 build — a structural TCG limitation with
# multi-threaded guests, not a version-specific bug). Fix: use the HOST's
# own native clang (`apt install clang lld`) targeting
# --target=<arch>-linux-android<API> directly, borrowing only DATA from the
# NDK (sysroot + 2 static-lib archives via -L) — never executing the NDK's
# own x86_64 clang binary.
#
# ⚠️ Do NOT use `-resource-dir=<NDK clang dir>` instead of `-L`: that
# replaces the host clang's own intrinsic headers (arm_neon.h etc.) with
# ones written for the NDK's (different) LLVM version, breaking real NEON
# code with "invalid conversion between vector type" / "unknown type name
# '__mfp8'" — only shows up on crates with real ASM/intrinsics (aws-lc-sys),
# not on a trivial crate, so it's easy to miss in a quick smoke test.
#
# Usage: tools/build-rust-android-libs.sh [NDK_HOME]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUST_DIR="$SCRIPT_DIR/../rust"
OUT_DIR="$SCRIPT_DIR/../jniLibs"

# Skip entirely if jniLibs/*.so are already newer than the Rust sources —
# this matters because the caller may be Gradle running INSIDE the amd64
# build container (see Dockerfile): `uname -m` there reports x86_64 (the
# container's own reported arch, not seer's real aarch64 CPU), so the
# arch-detection below would wrongly take the cargo-ndk branch — and cargo
# isn't installed in that container anymore (Rust builds on the true
# aarch64 host, outside any container). Gradle's own up-to-date tracking
# doesn't help here either: a fresh container has no build/ state to know
# this task already ran on the host. So check freshness directly instead
# of trusting either uname -m or Gradle's cache.
all_fresh=1
for abi in arm64-v8a armeabi-v7a x86_64; do
    so="$OUT_DIR/$abi/librdp_transport.so"
    if [ ! -f "$so" ]; then
        all_fresh=0
        break
    fi
    if [ -n "$(find "$RUST_DIR/src" "$RUST_DIR/Cargo.toml" "$RUST_DIR/Cargo.lock" -type f -newer "$so" 2>/dev/null)" ]; then
        all_fresh=0
        break
    fi
done
if [ "$all_fresh" = "1" ]; then
    echo "==> jniLibs/*.so already newer than rust/src — skipping (likely pre-built on the real host)"
    exit 0
fi

NDK_HOME="${1:-${ANDROID_NDK_HOME:-}}"
if [ -z "$NDK_HOME" ]; then
    NDK_HOME="$(ls -d "$HOME/Android/Sdk/ndk"/*/ 2>/dev/null | sort -V | tail -1)"
fi
if [ -z "$NDK_HOME" ] || [ ! -d "$NDK_HOME" ]; then
    echo "ERROR: NDK not found (set ANDROID_NDK_HOME, or pass as arg1)" >&2
    exit 1
fi
NDK_HOME="${NDK_HOME%/}"
export ANDROID_NDK_HOME="$NDK_HOME"

cd "$RUST_DIR"
mkdir -p "$OUT_DIR"

if [ "$(uname -m)" != "aarch64" ]; then
    echo "==> non-aarch64 host — using cargo-ndk (unaffected path)"
    cargo ndk -o "$OUT_DIR" -t arm64-v8a -t armeabi-v7a -t x86_64 build --release
    echo "==> Done. Libraries in $OUT_DIR:"
    find "$OUT_DIR" -name "*.so" -type f
    exit 0
fi

echo "==> aarch64 host detected — building natively, no qemu, no cargo-ndk"

if ! command -v clang >/dev/null 2>&1; then
    echo "ERROR: need native aarch64 'clang'+'lld' (sudo apt-get install clang lld)" >&2
    exit 1
fi

HOST_PREBUILT="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$HOST_PREBUILT/sysroot"
CLANG_VER="$(basename "$(find "$HOST_PREBUILT/lib/clang" -maxdepth 1 -mindepth 1 -type d | head -1)")"
RESLIB="$HOST_PREBUILT/lib/clang/$CLANG_VER/lib/linux"
AR_BIN="$(command -v llvm-ar-18 || command -v llvm-ar || command -v ar)"

WRAPDIR="$(mktemp -d)"
trap 'rm -rf "$WRAPDIR"' EXIT

make_wrapper() {
    local name="$1" target="$2" archdir="$3"
    # "$@" FIRST, --target/--sysroot LAST: cc-rs (aws-lc-sys's build.rs)
    # appends its own bare --target=<rust-triple> (no API level) after
    # whatever CC_<target> points to. clang takes the LAST --target= on the
    # line, so ours must come after "$@" or it silently loses to the bare
    # one and falls back to a too-old default minSdkVersion — surfaces as
    # "call to undeclared function 'getentropy'" (added API 28), which looks
    # like a sysroot/header problem but is really flag ordering.
    cat > "$WRAPDIR/$name" <<EOF
#!/bin/bash
exec clang "\$@" --target=$target --sysroot="$SYSROOT" -rtlib=compiler-rt -L"$RESLIB" -L"$RESLIB/$archdir"
EOF
    chmod +x "$WRAPDIR/$name"
}

make_wrapper aarch64-clang aarch64-linux-android31    aarch64
make_wrapper x86_64-clang  x86_64-linux-android31     x86_64
make_wrapper armv7-clang   armv7a-linux-androideabi30 arm

echo "==> Building aarch64-linux-android (arm64-v8a)..."
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$WRAPDIR/aarch64-clang"
export CC_aarch64_linux_android="$WRAPDIR/aarch64-clang"
export CXX_aarch64_linux_android="$WRAPDIR/aarch64-clang"
export AR_aarch64_linux_android="$AR_BIN"
cargo build --target aarch64-linux-android --release
mkdir -p "$OUT_DIR/arm64-v8a"
cp "target/aarch64-linux-android/release/librdp_transport.so" "$OUT_DIR/arm64-v8a/librdp_transport.so"

echo "==> Building x86_64-linux-android (x86_64)..."
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$WRAPDIR/x86_64-clang"
export CC_x86_64_linux_android="$WRAPDIR/x86_64-clang"
export CXX_x86_64_linux_android="$WRAPDIR/x86_64-clang"
export AR_x86_64_linux_android="$AR_BIN"
cargo build --target x86_64-linux-android --release
mkdir -p "$OUT_DIR/x86_64"
cp "target/x86_64-linux-android/release/librdp_transport.so" "$OUT_DIR/x86_64/librdp_transport.so"

echo "==> Building armv7-linux-androideabi (armeabi-v7a)..."
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$WRAPDIR/armv7-clang"
export CC_armv7_linux_androideabi="$WRAPDIR/armv7-clang"
export CXX_armv7_linux_androideabi="$WRAPDIR/armv7-clang"
export AR_armv7_linux_androideabi="$AR_BIN"
cargo build --target armv7-linux-androideabi --release
mkdir -p "$OUT_DIR/armeabi-v7a"
cp "target/armv7-linux-androideabi/release/librdp_transport.so" "$OUT_DIR/armeabi-v7a/librdp_transport.so"

echo "==> Done. Libraries in $OUT_DIR:"
find "$OUT_DIR" -name "*.so" -type f
