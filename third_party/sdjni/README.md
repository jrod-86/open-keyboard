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

Patch to ggml (in the stable-diffusion.cpp checkout): `ggml/src/ggml-cpu/ggml-cpu.c` gets a global
`volatile int ggml_cpu_abort_flag` that the graph loop checks next to `cplan->abort_callback`, so Stop ends a long
drawing step part way through instead of waiting for it to finish.

A second copy, `libsdjni_i8mm.so`, is built the same way with `armv8.6-a+dotprod+i8mm+fp16` in place of
`armv8.2-a+dotprod+fp16`; the app loads it when /proc/cpuinfo lists i8mm.
