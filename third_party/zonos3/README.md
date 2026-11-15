# zonos3-server for Android (Text to speech tile, Zonos engine)

`app/src/main/jniLibs/arm64-v8a/libzonos3_server.so` is Zyphra's `zonos3-server` (MIT, see LICENSE-MIT.txt and
NOTICE.txt), cross-compiled for Android. It is an executable, not a library: the app runs it as a local server on
127.0.0.1 inside the `:tts` process (tts/ZonosTts.kt). Model weights (ZONOS3-Mini, Apache-2.0) are downloaded at run
time; the ten English voices' reference codes (from the model repo's `gguf/voices/English/*.codes.npy`) are in
`app/src/main/assets/zonos-voices/` as JSON.

Source: https://github.com/Zyphra/ZONOS3-Mini at 96d9e0ec1e39083ed336ac78f24e3dbc93721c0f, submodules ggml and
third_party/zyphra-textnorm (Rust, Apache-2.0 / NeMo-derived).

Build (NDK 28.0.13004108, Rust with `rustup target add aarch64-linux-android`):

```bash
git clone https://github.com/Zyphra/ZONOS3-Mini && cd ZONOS3-Mini && git checkout 96d9e0ec
git submodule update --init --depth 1 servers/ggml/ggml third_party/zyphra-textnorm
git apply /path/to/android-cmake.patch     # Android changes, see below
cd servers/ggml
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$TC/aarch64-linux-android28-clang \
  CC_aarch64_linux_android=$TC/aarch64-linux-android28-clang AR_aarch64_linux_android=$TC/llvm-ar
cmake -B build-a86 -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
  -DBUILD_SHARED_LIBS=OFF -DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm+fp16 -DBUILD_TESTING=OFF
cmake --build build-a86 -j8 --target zonos3-server
$TC/llvm-strip -o libzonos3_server.so build-a86/zonos3-server
```

Only the ARMv8.6 (i8mm) build is shipped, to keep the APK under GitHub's 100 MB file limit; phones without i8mm get
Kokoro only.

## Open Keyboard changes (android-cmake.patch)

- CMake lets cargo build zyphra-textnorm for aarch64-linux-android.
- `zonos3-server encode-reference <zac-encoder.gguf> <ssl-teacher.gguf> --audio clip.npy codes.npy` runs the reference
  encoder (src/zac-encoder-cli.cpp, built in through src/server/encode-reference.cpp), so "Make a voice" needs no
  second binary.
- The encoder's convolutional front end runs in 16 overlapping time chunks (ZAC_FRONT_CHUNKS), which gives identical
  codes with peak memory down from 5.5 GB to 2.4 GB.

The encoder and teacher the app downloads are q8_0 copies (`quantize-cli <in> <out> q8_0`) kept on this repo's
`voice-clone-models` branch; see its README.
