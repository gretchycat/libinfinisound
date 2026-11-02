# JNI entry points in openmpt_jni.c use this class name.
-keep class com.infinifolder.audio.tracker.OpenMpt { *; }

# libVLC callbacks bridge only the common input/PCM interfaces.
-keep class com.infinifolder.audio.LibVlcPcmNative { *; }
-keep interface com.infinifolder.audio.AudioInput { *; }
-keep interface com.infinifolder.audio.AudioBytes { *; }

-keep class com.infinifolder.audio.FluidLiteNative { *; }
-keep class com.infinifolder.audio.GameMusicNative { *; }

-keep class com.infinifolder.audio.LibVlcAvNative { *; }

-keep class com.infinifolder.audio.SmafNative { *; }
