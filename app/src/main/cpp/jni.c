// JNI bridge between the Kotlin app and whisper.cpp (on-device speech recognition).
#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DhivehiWhisper", __VA_ARGS__)
#else
#include <stdio.h>
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

#define FN(name) Java_mv_dhivehi_subtitles_whisper_WhisperLib_##name

static atomic_bool g_abort = false;

static bool abort_cb(void *data) {
    (void) data;
    return atomic_load(&g_abort);
}

struct progress_data {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
};

static void progress_cb(struct whisper_context *ctx, struct whisper_state *state, int progress, void *user_data) {
    (void) ctx; (void) state;
    struct progress_data *d = (struct progress_data *) user_data;
    if (d == NULL || d->listener == NULL || d->method == NULL) return;
    (*d->env)->CallVoidMethod(d->env, d->listener, d->method, (jint) progress);
    if ((*d->env)->ExceptionCheck(d->env)) (*d->env)->ExceptionClear(d->env);
}

JNIEXPORT jlong JNICALL FN(initContext)(JNIEnv *env, jobject thiz, jstring modelPath) {
    (void) thiz;
    const char *path = (*env)->GetStringUTFChars(env, modelPath, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, modelPath, path);
    if (ctx == NULL) LOGI("Failed to load model");
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL FN(freeContext)(JNIEnv *env, jobject thiz, jlong ptr) {
    (void) env; (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ptr;
    if (ctx != NULL) whisper_free(ctx);
}

JNIEXPORT void JNICALL FN(requestAbort)(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    atomic_store(&g_abort, true);
}

JNIEXPORT jint JNICALL FN(transcribe)(JNIEnv *env, jobject thiz, jlong ptr, jfloatArray samples,
                                      jint nThreads, jstring language, jobject listener) {
    (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ptr;
    if (ctx == NULL) return -100;
    atomic_store(&g_abort, false);

    struct progress_data pd = {env, listener, NULL};
    if (listener != NULL) {
        jclass cls = (*env)->GetObjectClass(env, listener);
        pd.method = (*env)->GetMethodID(env, cls, "onProgress", "(I)V");
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); pd.method = NULL; }
    }

    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = nThreads;
    p.translate = false;
    p.language = lang;
    p.detect_language = false;
    p.no_context = true;          // reduces repetition loops on long videos
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.token_timestamps = true;    // needed for word-by-word sync
    p.max_len = 0;
    p.suppress_blank = true;
    p.greedy.best_of = 2;
    p.progress_callback = progress_cb;
    p.progress_callback_user_data = &pd;
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = NULL;

    jsize n = (*env)->GetArrayLength(env, samples);
    jfloat *data = (*env)->GetFloatArrayElements(env, samples, NULL);
    int r = whisper_full(ctx, p, data, n);
    (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language, lang);

    if (atomic_load(&g_abort)) return -2;
    return r;
}

JNIEXPORT jint JNICALL FN(segmentCount)(JNIEnv *env, jobject thiz, jlong ptr) {
    (void) env; (void) thiz;
    return whisper_full_n_segments((struct whisper_context *) (intptr_t) ptr);
}

JNIEXPORT jlong JNICALL FN(segmentT0)(JNIEnv *env, jobject thiz, jlong ptr, jint seg) {
    (void) env; (void) thiz;
    return whisper_full_get_segment_t0((struct whisper_context *) (intptr_t) ptr, seg);
}

JNIEXPORT jlong JNICALL FN(segmentT1)(JNIEnv *env, jobject thiz, jlong ptr, jint seg) {
    (void) env; (void) thiz;
    return whisper_full_get_segment_t1((struct whisper_context *) (intptr_t) ptr, seg);
}

JNIEXPORT jint JNICALL FN(tokenCount)(JNIEnv *env, jobject thiz, jlong ptr, jint seg) {
    (void) env; (void) thiz;
    return whisper_full_n_tokens((struct whisper_context *) (intptr_t) ptr, seg);
}

JNIEXPORT jboolean JNICALL FN(isSpecialToken)(JNIEnv *env, jobject thiz, jlong ptr, jint seg, jint tok) {
    (void) env; (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ptr;
    whisper_token id = whisper_full_get_token_id(ctx, seg, tok);
    return id >= whisper_token_eot(ctx) ? JNI_TRUE : JNI_FALSE;
}

// Raw UTF-8 bytes: one Thaana letter can be split across two tokens, so Kotlin joins bytes before decoding.
JNIEXPORT jbyteArray JNICALL FN(tokenBytes)(JNIEnv *env, jobject thiz, jlong ptr, jint seg, jint tok) {
    (void) thiz;
    const char *text = whisper_full_get_token_text((struct whisper_context *) (intptr_t) ptr, seg, tok);
    jsize len = text ? (jsize) strlen(text) : 0;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (len > 0) (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) text);
    return arr;
}

// Token start/end in centiseconds, and probability (x1000).
JNIEXPORT jlongArray JNICALL FN(tokenTiming)(JNIEnv *env, jobject thiz, jlong ptr, jint seg, jint tok) {
    (void) thiz;
    whisper_token_data d = whisper_full_get_token_data((struct whisper_context *) (intptr_t) ptr, seg, tok);
    jlong vals[3] = {(jlong) d.t0, (jlong) d.t1, (jlong) (d.p * 1000.0f)};
    jlongArray arr = (*env)->NewLongArray(env, 3);
    (*env)->SetLongArrayRegion(env, arr, 0, 3, vals);
    return arr;
}

JNIEXPORT jstring JNICALL FN(systemInfo)(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
