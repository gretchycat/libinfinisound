#!/bin/sh
set -eu
native_src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
native_output=$1
native_java_home=$2
mkdir -p "$native_output/arm64-v8a"
clang -std=gnu11 -O2 -fPIC -shared -Wall -Wextra \
    -I"$native_java_home/include" -I"$native_java_home/include/linux" \
    -I"$native_src/third_party/ffmpeg/include" \
    "$native_src/vlc_pcm_jni.c" "$native_src/native_synthesis.c" "$native_src/av_pcm_jni.c" -ldl -lm \
    -Wl,-z,max-page-size=16384 -Wl,-soname,libinfinivlcpcm.so \
    -o "$native_output/arm64-v8a/libinfinivlcpcm.so"
