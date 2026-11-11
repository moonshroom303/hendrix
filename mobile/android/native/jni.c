#include <jni.h>
#include <stdlib.h>
#include <limits.h>
#include "session.h"

#define JNI_METHOD(ret, name) JNIEXPORT ret JNICALL Java_io_moonshroom_hendrix_NativeHub_##name
#define HUB ((HendrixHub *)(intptr_t)hub)
#define QUEUE ((HendrixAudioQueue *)(intptr_t)queue)

typedef struct { JNIEnv *env; jobject self; jmethodID send; } JavaOutput;

static JavaOutput output(JNIEnv *env, jobject self) {
    jclass cls = (*env)->GetObjectClass(env, self);
    jmethodID send = (*env)->GetMethodID(env, cls, "onMidi", "(JIII)V");
    (*env)->DeleteLocalRef(env, cls);
    return (JavaOutput){env, self, send};
}

static void emit(void *ptr, uint64_t sample, uint8_t status, uint8_t a, uint8_t b) {
    JavaOutput *out = ptr;
    if (out->send && !(*out->env)->ExceptionCheck(out->env))
        (*out->env)->CallVoidMethod(out->env, out->self, out->send, (jlong)sample, (jint)status, (jint)a, (jint)b);
}

JNI_METHOD(jlong, create)(JNIEnv *env, jobject self) { return (jlong)(intptr_t)hub_create(); }
JNI_METHOD(void, destroy)(JNIEnv *env, jobject self, jlong hub) { hub_destroy(HUB); }
JNI_METHOD(jboolean, setAudio)(JNIEnv *env, jobject self, jlong hub, jfloat rate, jfloat reference, jboolean poly) {
    return hub_set_audio(HUB, rate, reference, poly);
}
JNI_METHOD(void, setReference)(JNIEnv *env, jobject self, jlong hub, jfloat hz) { hub_set_reference(HUB, hz); }
JNI_METHOD(void, guitarParameters)(JNIEnv *env, jobject self, jlong hub, jfloatArray values) {
    float parameters[20];
    if (!values || (*env)->GetArrayLength(env, values) != 20) return;
    (*env)->GetFloatArrayRegion(env, values, 0, 20, parameters);
    if (!(*env)->ExceptionCheck(env)) hub_set_guitar_parameters(HUB, parameters, 20);
}
JNI_METHOD(jfloatArray, guitarMetrics)(JNIEnv *env, jobject self, jlong hub) {
    HendrixMetrics metrics;
    uint8_t notes[128];
    if (!hub_guitar_metrics(HUB, &metrics, notes)) return NULL;
    jfloat values[132] = {metrics.level, metrics.window, metrics.bend, (jfloat)metrics.attacks};
    for (size_t n = 0; n < 128; n++) values[n + 4] = notes[n];
    jfloatArray result = (*env)->NewFloatArray(env, 132);
    if (result) (*env)->SetFloatArrayRegion(env, result, 0, 132, values);
    return result;
}
JNI_METHOD(void, resetAudio)(JNIEnv *env, jobject self, jlong hub, jlong ms) {
    JavaOutput out = output(env, self);
    hub_reset_audio(HUB, ms, emit, &out);
}
JNI_METHOD(jfloatArray, tuner)(JNIEnv *env, jobject self, jlong hub) {
    HendrixTunerReading reading;
    if (!hub_tuner(HUB, &reading)) return NULL;
    float values[] = {reading.hz, reading.note, reading.cents, reading.level_db};
    jfloatArray result = (*env)->NewFloatArray(env, 4);
    if (result) (*env)->SetFloatArrayRegion(env, result, 0, 4, values);
    return result;
}
JNI_METHOD(void, note)(JNIEnv *env, jobject self, jlong hub, jint pitch, jint velocity, jlong ms) {
    JavaOutput out = output(env, self);
    if (pitch >= 0 && pitch <= 127 && velocity >= 0 && velocity <= 127)
        hub_key(HUB, pitch, velocity, ms, emit, &out);
}
JNI_METHOD(void, releaseKeys)(JNIEnv *env, jobject self, jlong hub, jlong ms) {
    JavaOutput out = output(env, self);
    hub_release_keys(HUB, ms, emit, &out);
}
JNI_METHOD(void, control)(JNIEnv *env, jobject self, jlong hub, jint controller, jint value, jlong ms) {
    JavaOutput out = output(env, self);
    if (controller >= 0 && controller <= 127 && value >= 0 && value <= 127)
        hub_control(HUB, controller, value, ms, emit, &out);
}
JNI_METHOD(jboolean, startTake)(JNIEnv *env, jobject self, jlong hub, jlong ms) { return hub_start_take(HUB, ms); }
JNI_METHOD(jboolean, stopTake)(JNIEnv *env, jobject self, jlong hub, jlong ms) { return hub_stop_take(HUB, ms); }
JNI_METHOD(jbyteArray, exportTake)(JNIEnv *env, jobject self, jlong hub) {
    size_t count = hub_export(HUB, NULL, 0);
    if (!count || count > INT_MAX) return NULL;
    uint8_t *bytes = malloc(count);
    if (!bytes) return NULL;
    if (hub_export(HUB, bytes, count) != count) { free(bytes); return NULL; }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)count);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)count, (jbyte *)bytes);
    free(bytes);
    return result;
}
JNI_METHOD(jlongArray, score)(JNIEnv *env, jobject self, jlong hub) {
    size_t count = hub_score(HUB, NULL, 0);
    if (count == 0 || count > 100000) return (*env)->NewLongArray(env, 0);
    HendrixScoreNote *notes = calloc(count, sizeof(*notes));
    jlong *values = calloc(count * 5, sizeof(*values));
    if (!notes || !values) { free(notes); free(values); return NULL; }
    hub_score(HUB, notes, count);
    for (size_t i = 0; i < count; i++) {
        values[i * 5] = notes[i].start_sample;
        values[i * 5 + 1] = notes[i].end_sample;
        values[i * 5 + 2] = notes[i].pitch;
        values[i * 5 + 3] = notes[i].guitar_string;
        values[i * 5 + 4] = notes[i].guitar_fret;
    }
    jlongArray result = (*env)->NewLongArray(env, (jsize)(count * 5));
    if (result) (*env)->SetLongArrayRegion(env, result, 0, (jsize)(count * 5), values);
    free(notes);
    free(values);
    return result;
}
JNI_METHOD(jlong, createQueue)(JNIEnv *env, jobject self) { return (jlong)(intptr_t)audio_queue_create(); }
JNI_METHOD(void, destroyQueue)(JNIEnv *env, jobject self, jlong queue) { audio_queue_destroy(QUEUE); }
JNI_METHOD(jboolean, push)(JNIEnv *env, jobject self, jlong queue, jfloatArray data, jint count) {
    float samples[1024];
    if (count <= 0 || count > 1024 || count > (*env)->GetArrayLength(env, data)) return false;
    (*env)->GetFloatArrayRegion(env, data, 0, count, samples);
    return audio_queue_push(QUEUE, samples, count);
}
JNI_METHOD(jboolean, drain)(JNIEnv *env, jobject self, jlong queue, jlong hub, jlong ms) {
    JavaOutput out = output(env, self);
    return audio_queue_drain(QUEUE, HUB, ms, emit, &out);
}
