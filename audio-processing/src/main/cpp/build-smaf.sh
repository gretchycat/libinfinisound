#!/bin/sh
set -eu
native_src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
native_output=$1
native_java_home=$2
mkdir -p "$native_output/arm64-v8a"
clang++ -std=c++20 -O2 -fPIC -shared -Wall -Wextra \
    -I"$native_java_home/include" -I"$native_java_home/include/linux" \
    "$native_src/smaf_jni.cpp" \
    "$native_src/third_party/smaf/smaf_file.cpp" \
    "$native_src/third_party/smaf/ma_fm_core.cpp" \
    "$native_src/third_party/smaf/smaf_voice.cpp" \
    "$native_src/third_party/smaf/ma_player.cpp" \
    -Wl,-z,max-page-size=16384 -Wl,-soname,libinfinismaf.so \
    -o "$native_output/arm64-v8a/libinfinismaf.so"
