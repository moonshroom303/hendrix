#include <jni.h>
#include <stdint.h>
#include "gm_synth.h"
#define JNI_FN(name) Java_io_moonshroom_hendrix_GeneralMidiSynth_##name
JNIEXPORT jlong JNICALL JNI_FN(create)(JNIEnv *env,jclass cls,jstring path,jint rate) {
    (void)cls; if(!path) return 0;
    const char *name=(*env)->GetStringUTFChars(env,path,NULL); if(!name) return 0;
    GmSynth *s=gm_create(name,rate); (*env)->ReleaseStringUTFChars(env,path,name); return (jlong)(intptr_t)s;
}
JNIEXPORT void JNICALL JNI_FN(destroy)(JNIEnv *env,jclass cls,jlong ptr) { (void)env;(void)cls;gm_destroy((GmSynth*)(intptr_t)ptr); }
JNIEXPORT void JNICALL JNI_FN(program)(JNIEnv *env,jclass cls,jlong ptr,jint part,jint value) { (void)env;(void)cls;gm_program((GmSynth*)(intptr_t)ptr,part,value); }
JNIEXPORT void JNICALL JNI_FN(message)(JNIEnv *env,jclass cls,jlong ptr,jint part,jint status,jint a,jint b) { (void)env;(void)cls;gm_midi((GmSynth*)(intptr_t)ptr,part,status,a,b); }
JNIEXPORT void JNICALL JNI_FN(panic)(JNIEnv *env,jclass cls,jlong ptr,jint part) { (void)env;(void)cls;gm_panic((GmSynth*)(intptr_t)ptr,part); }
JNIEXPORT void JNICALL JNI_FN(render)(JNIEnv *env,jclass cls,jlong ptr,jfloatArray samples) {
    (void)cls; if(!ptr || !samples) return;
    jsize size=(*env)->GetArrayLength(env,samples); if(size<=0 || size>8192 || size%2) return;
    float *data=(*env)->GetFloatArrayElements(env,samples,NULL); if(!data) return;
    gm_render((GmSynth*)(intptr_t)ptr,data,size/2); (*env)->ReleaseFloatArrayElements(env,samples,data,0);
}
