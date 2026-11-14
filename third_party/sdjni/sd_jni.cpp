// SPDX-License-Identifier: GPL-3.0-only
// JNI bridge from Open Keyboard's Image maker to stable-diffusion.cpp (MIT, github.com/leejet/stable-diffusion.cpp).
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include "stable-diffusion.h"

extern "C" volatile int ggml_cpu_abort_flag; // patched into ggml-cpu.c, see README

static JavaVM* g_vm = nullptr;
static jclass g_cls = nullptr;
static jmethodID g_progress = nullptr;
static jmethodID g_stage = nullptr;

jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = env->FindClass("helium314/keyboard/media/ImageGen");
    if (c) {
        g_cls = reinterpret_cast<jclass>(env->NewGlobalRef(c));
        g_progress = env->GetStaticMethodID(g_cls, "onProgress", "(II)V");
        if (env->ExceptionCheck()) { env->ExceptionClear(); g_progress = nullptr; }
        g_stage = env->GetStaticMethodID(g_cls, "onStage", "(Ljava/lang/String;JJ)V");
        if (env->ExceptionCheck()) { env->ExceptionClear(); g_stage = nullptr; }
    }
    return JNI_VERSION_1_6;
}

static void progress_cb(int step, int steps, float, void*) {
    if (!g_vm || !g_progress) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallStaticVoidMethod(g_cls, g_progress, step, steps);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

static void stage_cb(const sd_generation_progress_t* p, void*) {
    if (!g_vm || !g_stage || !p || !p->stage) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jstring st = env->NewStringUTF(p->stage);
    env->CallStaticVoidMethod(g_cls, g_stage, st, (jlong)p->completed, (jlong)p->total);
    env->DeleteLocalRef(st);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

static void log_cb(enum sd_log_level_t level, const char* text, void*) {
    if (level >= SD_LOG_INFO) __android_log_print(ANDROID_LOG_INFO, "ImageMaker", "%s", text);
}

extern "C" JNIEXPORT jlong JNICALL
Java_helium314_keyboard_media_ImageGen_nativeLoad(JNIEnv* env, jobject, jstring model, jint threads) {
    sd_set_log_callback(log_cb, nullptr);
    sd_set_progress_callback(progress_cb, nullptr);
    const char* path = env->GetStringUTFChars(model, nullptr);
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path;
    p.n_threads = threads;
    p.enable_mmap = true;
    sd_ctx_t* ctx = new_sd_ctx(&p);
    if (ctx) sd_set_generation_progress_callback(ctx, stage_cb, nullptr);
    env->ReleaseStringUTFChars(model, path);
    return reinterpret_cast<jlong>(ctx);
}

// Models that come as separate files: the picture model, the text model and the decoder.
extern "C" JNIEXPORT jlong JNICALL
Java_helium314_keyboard_media_ImageGen_nativeLoadParts(JNIEnv* env, jobject, jstring diffusion, jstring llm, jstring vae, jint threads) {
    sd_set_log_callback(log_cb, nullptr);
    sd_set_progress_callback(progress_cb, nullptr);
    const char* d = env->GetStringUTFChars(diffusion, nullptr);
    const char* l = env->GetStringUTFChars(llm, nullptr);
    const char* v = env->GetStringUTFChars(vae, nullptr);
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.diffusion_model_path = d;
    p.llm_path = l;
    p.vae_path = v;
    p.n_threads = threads;
    p.enable_mmap = true;
    sd_ctx_t* ctx = new_sd_ctx(&p);
    if (ctx) sd_set_generation_progress_callback(ctx, stage_cb, nullptr);
    env->ReleaseStringUTFChars(diffusion, d);
    env->ReleaseStringUTFChars(llm, l);
    env->ReleaseStringUTFChars(vae, v);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_helium314_keyboard_media_ImageGen_nativeGenerate(JNIEnv* env, jobject, jlong handle, jstring prompt, jstring negative,
                                                      jint width, jint height, jint steps, jfloat cfg, jlong seed) {
    auto* ctx = reinterpret_cast<sd_ctx_t*>(handle);
    if (!ctx) return nullptr;
    ggml_cpu_abort_flag = 0;
    const char* pr = env->GetStringUTFChars(prompt, nullptr);
    const char* ng = env->GetStringUTFChars(negative, nullptr);
    sd_img_gen_params_t g;
    sd_img_gen_params_init(&g);
    g.prompt = pr;
    g.negative_prompt = ng;
    g.width = width;
    g.height = height;
    g.seed = seed;
    g.batch_count = 1;
    g.sample_params.sample_steps = steps;
    g.sample_params.guidance.txt_cfg = cfg;
    sd_image_generation_result_t* res = nullptr;
    bool ok = generate_image(ctx, &g, &res);
    env->ReleaseStringUTFChars(prompt, pr);
    env->ReleaseStringUTFChars(negative, ng);
    if (!ok || !res || res->count < 1 || !res->items[0].image.data) {
        if (res) free_sd_image_generation_result(res);
        return nullptr;
    }
    sd_image_t img = res->items[0].image;
    // returned as width, height (4 bytes each, big-endian) followed by RGB pixels
    jsize n = (jsize)(img.width * img.height * 3);
    jbyteArray out = env->NewByteArray(8 + n);
    jbyte head[8] = {(jbyte)(img.width >> 24), (jbyte)(img.width >> 16), (jbyte)(img.width >> 8), (jbyte)img.width,
                     (jbyte)(img.height >> 24), (jbyte)(img.height >> 16), (jbyte)(img.height >> 8), (jbyte)img.height};
    env->SetByteArrayRegion(out, 0, 8, head);
    if (img.channel == 3) {
        env->SetByteArrayRegion(out, 8, n, reinterpret_cast<jbyte*>(img.data));
    } else {
        jbyte* rgb = new jbyte[n];
        for (uint32_t i = 0; i < img.width * img.height; i++)
            for (int c = 0; c < 3; c++) rgb[i * 3 + c] = (jbyte)img.data[i * img.channel + (c < (int)img.channel ? c : 0)];
        env->SetByteArrayRegion(out, 8, n, rgb);
        delete[] rgb;
    }
    free_sd_image_generation_result(res);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_media_ImageGen_nativeCancel(JNIEnv*, jobject, jlong handle) {
    ggml_cpu_abort_flag = 1; // stops the step being worked on now, not just before the next one
    if (handle) sd_cancel_generation(reinterpret_cast<sd_ctx_t*>(handle), SD_CANCEL_ALL);
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_media_ImageGen_nativeFree(JNIEnv*, jobject, jlong handle) {
    if (handle) free_sd_ctx(reinterpret_cast<sd_ctx_t*>(handle));
}
