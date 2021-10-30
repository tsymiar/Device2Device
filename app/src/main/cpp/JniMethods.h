#ifndef DEVIDROID_JNIMETHODS_H
#define DEVIDROID_JNIMETHODS_H

#define Callback_WRAPPER(func) Java_com_tsymiar_devidroid_wrapper_CallbackWrapper_##func
#define CPP_FUNC_CALL(func) JNICALL Callback_WRAPPER(func)

#define View_WRAPPER(func) Java_com_tsymiar_devidroid_wrapper_ViewWrapper_##func
#define CPP_FUNC_VIEW(func) JNICALL View_WRAPPER(func)

#define Time_WRAPPER(func) Java_com_tsymiar_devidroid_wrapper_TimeWrapper_##func
#define CPP_FUNC_TIME(func) JNICALL Time_WRAPPER(func)

#define File_WRAPPER(func) Java_com_tsymiar_devidroid_wrapper_FileWrapper_##func
#define CPP_FUNC_FILE(func) JNICALL File_WRAPPER(func)

#define Network_WRAPPER(func) Java_com_tsymiar_devidroid_wrapper_NetWrapper_##func
#define CPP_FUNC_NETWORK(func) JNICALL Network_WRAPPER(func)

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void CPP_FUNC_CALL(initJvmEnv)(JNIEnv *env, jclass clazz, jstring class_name);

JNIEXPORT jstring CPP_FUNC_CALL(stringGetJNI)(JNIEnv *env, jobject clazz);
JNIEXPORT jlong CPP_FUNC_CALL(timeSetJNI)(JNIEnv *env, jobject clazz, jbyteArray time, jint len);
JNIEXPORT jint CPP_FUNC_CALL(KaiSubscribe)(JNIEnv *env, jclass clazz, jstring addr, jint port, jstring topic, jint viewId);
JNIEXPORT void CPP_FUNC_CALL(KaiPublish)(JNIEnv *env, jclass clazz, jstring topic, jstring payload);

JNIEXPORT void
CPP_FUNC_CALL(callJavaMethod)(JNIEnv *env, jclass clazz, jstring method, jint action,
                              jstring content, jboolean statics = JNI_TRUE);
JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateEglSurface)(JNIEnv *env, jclass, jobject texture, jstring url);

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateSurfaceView)(JNIEnv *env, jclass, jobject texture, jint selection);

JNIEXPORT jlong JNICALL CPP_FUNC_TIME(getAbsoluteTimestamp)(JNIEnv *, jclass);
JNIEXPORT jlong JNICALL CPP_FUNC_TIME(getBootTimestamp)(JNIEnv *, jclass);

JNIEXPORT jint JNICALL CPP_FUNC_FILE(convertAudioFiles)(JNIEnv *, jclass, jstring, jstring);
JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(sendUdpData)(JNIEnv *, jclass, jstring text, jint len);
JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startServer)(JNIEnv *env, jclass);
#ifdef __cplusplus
}
#endif

#endif //DEVIDROID_JNIMETHODS_H
