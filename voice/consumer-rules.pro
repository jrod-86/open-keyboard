# Keep ONNX Runtime and the voice module, which use JNI and reflection
-keep class ai.onnxruntime.** { *; }
-keep class com.whisperonnx.** { *; }
-keep class com.konovalov.vad.** { *; }
-keep class com.github.houbb.opencc4j.** { *; }
-dontwarn **
