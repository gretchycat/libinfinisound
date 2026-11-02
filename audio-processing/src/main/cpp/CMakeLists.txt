cmake_minimum_required(VERSION 3.22.1)
project(infinivlcpcm C CXX)
add_library(infinivlcpcm SHARED vlc_pcm_jni.c native_synthesis.c av_pcm_jni.c)
target_include_directories(infinivlcpcm PRIVATE third_party/ffmpeg/include)
target_link_libraries(infinivlcpcm dl m)
target_link_options(infinivlcpcm PRIVATE "-Wl,-z,max-page-size=16384")

add_library(infinismaf SHARED smaf_jni.cpp
    third_party/smaf/smaf_file.cpp third_party/smaf/ma_fm_core.cpp
    third_party/smaf/smaf_voice.cpp third_party/smaf/ma_player.cpp)
target_compile_features(infinismaf PRIVATE cxx_std_20)
target_link_options(infinismaf PRIVATE "-Wl,-z,max-page-size=16384")
