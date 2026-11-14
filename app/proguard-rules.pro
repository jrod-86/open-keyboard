# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# sherpa-onnx speech recognition uses JNI, keep its classes and fields as they are
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ONNX Runtime's native code creates its Java objects (TensorInfo, OnnxTensor, ...) by name
-keep class ai.onnxruntime.** { *; }

# Image maker: native code calls these back by name
-keep class helium314.keyboard.media.ImageGen {
    public static void onProgress(int, int);
    public static void onStage(java.lang.String, long, long);
}
