#ifndef IPCROID_JNIINC_H
#define IPCROID_JNIINC_H

#include "../cpp/jniComm.h"

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void CPP_FUNC_CALL(initJvmEnv)(JNIEnv *env, jclass clazz, jstring class_name);

JNIEXPORT jstring CPP_FUNC_CALL(stringFromJNI)(JNIEnv *env, jobject clazz);

JNIEXPORT void
CPP_FUNC_CALL(callJavaStaticMethod)(JNIEnv *env, jclass clazz, jstring method, jint action,
                                    jstring content);
JNIEXPORT void
CPP_FUNC_CALL(callJavaNonstaticMethod)(JNIEnv *env, jclass clazz, jstring method, jint action,
                                       jstring content);

#ifdef __cplusplus
}
#endif

#endif //IPCROID_JNIINC_H
