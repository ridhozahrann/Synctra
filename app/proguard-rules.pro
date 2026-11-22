# Keep Media3 FFmpeg audio renderer & JNI methods loaded reflectively by DefaultRenderersFactory
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**
