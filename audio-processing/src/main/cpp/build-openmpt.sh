#!/bin/sh
set -eu
native_src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
python3 "$native_src/../../../../scripts/verify-termux-native.py"
native_output=$1
native_java_home=$2
mkdir -p "$native_output/arm64-v8a"

clang -std=gnu11 -O2 -fPIC -shared -Wall -Wextra \
    -I"${PREFIX:?Set PREFIX to the Termux installation prefix}"/include \
    -I"$native_java_home/include" -I"$native_java_home/include/linux" \
    "$native_src/openmpt_jni.c" \
    -lopenmpt -Wl,-z,max-page-size=16384 -Wl,-soname,libinfiniopenmpt.so \
    -o "$native_output/arm64-v8a/libinfiniopenmpt.so"

cp "${PREFIX:?Set PREFIX to the Termux installation prefix}"/lib/libopenmpt.so \
   "${PREFIX:?Set PREFIX to the Termux installation prefix}"/lib/libmpg123.so \
   "${PREFIX:?Set PREFIX to the Termux installation prefix}"/lib/libvorbis.so \
   "${PREFIX:?Set PREFIX to the Termux installation prefix}"/lib/libvorbisfile.so \
   "${PREFIX:?Set PREFIX to the Termux installation prefix}"/lib/libogg.so \
   "${PREFIX:?Set PREFIX to the Termux installation prefix}"/lib/libc++_shared.so \
   "$native_output/arm64-v8a/"

patchelf --replace-needed libz.so.1 libz.so "$native_output/arm64-v8a/libopenmpt.so"

for lib in "$native_output/arm64-v8a/"*.so; do
    patchelf --remove-rpath "$lib" || true
done
