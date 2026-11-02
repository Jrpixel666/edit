#!/usr/bin/env bash
# Run once on your PC before building. Pulls llama.cpp + a static arm64 proot.
set -e; cd "$(dirname "$0")/.."
[ -d app/src/main/cpp/llama.cpp ] || git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
J=app/src/main/jniLibs/arm64-v8a; mkdir -p $J
# Termux's proot .deb ships proot + loader built for Android/bionic
curl -L -o /tmp/proot.deb "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/$(curl -s https://packages.termux.dev/apt/termux-main/pool/main/p/proot/ | grep -o 'proot_[^"]*aarch64.deb' | sort -V | tail -1)"
mkdir -p /tmp/pd && cd /tmp/pd && ar x /tmp/proot.deb && tar xf data.tar.* && cd - >/dev/null
cp /tmp/pd/data/data/com.termux/files/usr/bin/proot $J/libproot.so
cp /tmp/pd/data/data/com.termux/files/usr/libexec/proot/loader $J/libproot-loader.so
# proot links libtalloc + libandroid-shmem from Termux. Android only packages jniLibs files whose
# name ends in exactly ".so", so libtalloc.so.2 ships as libtalloc.so; Shell.kt materializes the
# versioned name at runtime (see libDir there) since a spawned proot process needs it to match
# proot's DT_NEEDED entry exactly.
curl -s https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/ | grep -o 'libtalloc_[^"]*aarch64.deb' | sort -V | tail -1 \
 | xargs -I{} curl -L -o /tmp/talloc.deb https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/{}
mkdir -p /tmp/tl && cd /tmp/tl && ar x /tmp/talloc.deb && tar xf data.tar.* && cd - >/dev/null
cp -L /tmp/tl/data/data/com.termux/files/usr/lib/libtalloc.so.2 $J/libtalloc.so

curl -s https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/ | grep -o 'libandroid-shmem_[^"]*aarch64.deb' | sort -V | tail -1 \
 | xargs -I{} curl -L -o /tmp/shmem.deb https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/{}
mkdir -p /tmp/sh && cd /tmp/sh && ar x /tmp/shmem.deb && tar xf data.tar.* && cd - >/dev/null
cp -L /tmp/sh/data/data/com.termux/files/usr/lib/libandroid-shmem.so $J/libandroid-shmem.so
echo "Done. Now: ./gradlew assembleRelease  (or open in Android Studio)"
