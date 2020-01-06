#ifndef DEVIDROID_JNICOMM_H
#define DEVIDROID_JNICOMM_H

#define Callback_WRAPPER(func) Java_com_tsymiar_devidroid_wrapper_CallbackWrapper_##func
#define CPP_FUNC_CALL(func) JNICALL Callback_WRAPPER(func)

#define View_WRAPPER(func) Java_com_tsymiar_devidroid_wrapper_ViewWrapper_##func
#define CPP_FUNC_VIEW(func) JNICALL View_WRAPPER(func)

#define Time_WRAPPER(func) Java_com_tsymiar_devidroid_wrapper_TimeWrapper_##func
#define CPP_FUNC_TIME(func) JNICALL Time_WRAPPER(func)

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void CPP_FUNC_CALL(initJvmEnv)(JNIEnv *env, jclass clazz, jstring class_name);

JNIEXPORT jstring CPP_FUNC_CALL(stringFromJNI)(JNIEnv *env, jobject clazz);

JNIEXPORT void
CPP_FUNC_CALL(callJavaMethod)(JNIEnv *env, jclass clazz, jstring method, jint action,
                              jstring content, jboolean statics = JNI_TRUE);
JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateSurfaceView)(JNIEnv *env, jclass, jobject texture, jint selection);

JNIEXPORT jlong JNICALL CPP_FUNC_TIME(getAbsoluteTimestamp)(JNIEnv *, jclass);
JNIEXPORT jlong JNICALL CPP_FUNC_TIME(getBootTimestamp)(JNIEnv *, jclass);
#ifdef __cplusplus
}
#endif

#endif //DEVIDROID_JNICOMM_H
