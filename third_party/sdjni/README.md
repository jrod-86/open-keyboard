# libsdjni (Image maker)

stable-diffusion.cpp (MIT, https://github.com/leejet/stable-diffusion.cpp, commit b521db48355fe2257a68100cda742900ed883999,
with its ggml submodule) plus `sd_jni.cpp`, built for arm64-v8a only. `vocab_slim.cpp` replaces the upstream vocab file so
only the CLIP tokenizer is built in (SD 1.x models), which keeps the library about 10 MB instead of 38 MB.

    cmake -S third_party/sdjni -B build -DSD_DIR=/path/to/stable-diffusion.cpp \
      -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
      -DCMAKE_BUILD_TYPE=Release -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 \
      -DCMAKE_C_FLAGS=-march=armv8.2-a+dotprod+fp16 -DCMAKE_CXX_FLAGS=-march=armv8.2-a+dotprod+fp16
    cmake --build build -j
    llvm-strip -o app/src/main/jniLibs/arm64-v8a/libsdjni.so build/libsdjni.so

Model: SDXS-512 Q8_0 GGUF (CreativeML OpenRAIL++-M), downloaded by the app on first use (see media/ImageGen.kt).
