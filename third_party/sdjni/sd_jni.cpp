// SPDX-License-Identifier: GPL-3.0-only
// JNI bridge from Open Keyboard's Image maker to stable-diffusion.cpp (MIT, github.com/leejet/stable-diffusion.cpp).
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include "stable-diffusion.h"

static void log_cb(enum sd_log_level_t level, const char* text, void*) {
    if (level >= SD_LOG_INFO) __android_log_print(ANDROID_LOG_INFO, "ImageMaker", "%s", text);
}

extern "C" JNIEXPORT jlong JNICALL
Java_helium314_keyboard_media_ImageGen_nativeLoad(JNIEnv* env, jobject, jstring model, jint threads) {
    sd_set_log_callback(log_cb, nullptr);
    const char* path = env->GetStringUTFChars(model, nullptr);
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path;
    p.n_threads = threads;
    p.enable_mmap = true;
    sd_ctx_t* ctx = new_sd_ctx(&p);
    env->ReleaseStringUTFChars(model, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_helium314_keyboard_media_ImageGen_nativeGenerate(JNIEnv* env, jobject, jlong handle, jstring prompt, jstring negative,
                                                      jint width, jint height, jint steps, jfloat cfg, jlong seed) {
    auto* ctx = reinterpret_cast<sd_ctx_t*>(handle);
    if (!ctx) return nullptr;
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
    if (handle) sd_cancel_generation(reinterpret_cast<sd_ctx_t*>(handle), SD_CANCEL_ALL);
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_media_ImageGen_nativeFree(JNIEnv*, jobject, jlong handle) {
    if (handle) free_sd_ctx(reinterpret_cast<sd_ctx_t*>(handle));
}
