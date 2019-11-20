#ifndef LOG_TAG
#define LOG_TAG "jniComm"
#endif

#include <mutex>
#include <string>
#include <common/logger.h>

#ifdef __cplusplus
extern "C" {
#endif
#include "../jni/jniInc.h"
#ifdef __cplusplus
}
#endif

#include "callback/CallJavaMethod.h"

jstring cstring2jstring(JNIEnv *env, const char *pat)
{
    jclass clz = (env)->FindClass("Ljava/lang/String;");
    jmethodID jmId = (env)->GetMethodID(clz, "<init>", "([BLjava/lang/String;)V");
    jbyteArray bytes = (env)->NewByteArray(strlen(pat));
    (env)->SetByteArrayRegion(bytes, 0, strlen(pat), (jbyte *) pat);
    jstring encoding = (env)->NewStringUTF("utf-8");
    return (jstring) (env)->NewObject(clz, jmId, bytes, encoding);
}

std::string jstring2cstring(JNIEnv *env, jstring jstr)
{
    char *rtn = nullptr;
    jclass clz = env->FindClass("java/lang/String");
    jstring encode = env->NewStringUTF("utf-8");
    jmethodID mid = env->GetMethodID(clz, "getBytes", "(Ljava/lang/String;)[B");
    auto barr = (jbyteArray) env->CallObjectMethod(jstr, mid, encode);
    auto len = static_cast<size_t>(env->GetArrayLength(barr));
    jbyte *ba = env->GetByteArrayElements(barr, JNI_FALSE);
    if (len > 0) {
        rtn = (char *) malloc(len + 1);
        memcpy(rtn, ba, len);
        rtn[len] = 0;
    }
    env->ReleaseByteArrayElements(barr, ba, 0);
    std::string temp(rtn);
    free(rtn);
    return temp;
}

jstring getPackageName(JNIEnv *env)
{
    jobject context = nullptr;
    jclass activity_thread_clz = env->FindClass("android/app/ActivityThread");
    if (activity_thread_clz != nullptr) {
        jmethodID get_Application = env->GetStaticMethodID(
                activity_thread_clz,
                "currentActivityThread",
                "()Landroid/app/ActivityThread;");
        if (get_Application != nullptr) {
            jobject currentActivityThread = env->CallStaticObjectMethod(
                    activity_thread_clz,
                    get_Application);
            jmethodID getal = env->GetMethodID(
                    activity_thread_clz,
                    "getApplication",
                    "()Landroid/app/Application;");
            context = env->CallObjectMethod(currentActivityThread, getal);
        }
    }
    if (context == nullptr) {
        LOGE("context is null!");
        return nullptr;
    }
    jclass activity = env->GetObjectClass(context);
    jmethodID methodId_pack = env->GetMethodID(activity, "getPackageName", "()Ljava/lang/String;");
    auto package = reinterpret_cast<jstring >( env->CallObjectMethod(context, methodId_pack));
    return package;
}

extern "C" {
extern jclass g_jniCls;
extern JavaVM *g_jniJVM;
extern std::string g_className;
extern std::mutex g_lock;

JNIEXPORT void CPP_FUNC_CALL(initJvmEnv)(JNIEnv *env, jclass, jstring class_name)
{
    int state = env->GetJavaVM(&g_jniJVM);
    g_className =
            // jstring2cstring(env, getPackageName(env))
            // + "." +
            jstring2cstring(env, class_name);
    LOGI("class_name = %s, state = %d.", g_className.c_str(), state);
}

#include "common/statics.h"

JNIEXPORT jstring CPP_FUNC_CALL(stringFromJNI)(
        JNIEnv *env,
        jobject /* this */)
{
    std::string hello = "C++ string of JNI!";
    char text[16] = {0x1a, 0x13, 0x00, 0x07,
                    static_cast<char>(0xcc), static_cast<char>(0xff),
                    static_cast<char>(0xe0), static_cast<char>(0x88)};
    Statics::printBuffer(text, 32);
    return env->NewStringUTF(hello.c_str());
}

JNIEXPORT void
CPP_FUNC_CALL(callJavaStaticMethod)(JNIEnv *env, jclass, jstring method, jint action,
                                    jstring content)
{
    CallJavaMethod::getInstance()->callStaticMethod(jstring2cstring(env, method),
                                                    static_cast<int>(action),
                                                    jstring2cstring(env, content).c_str());
}

JNIEXPORT void
CPP_FUNC_CALL(callJavaNonstaticMethod)(JNIEnv *env, jclass, jstring method, jint action,
                                       jstring content)
{
    CallJavaMethod::getInstance()->callNonstaticMethod(jstring2cstring(env, method),
                                                       static_cast<int>(action),
                                                       jstring2cstring(env, content).c_str());
}
}