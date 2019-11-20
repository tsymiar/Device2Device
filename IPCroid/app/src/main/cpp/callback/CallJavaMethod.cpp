#ifndef LOG_TAG
#define LOG_TAG "CallJavaMethod"
#endif

#include <algorithm>
#include <common/logger.h>

#include "CallJavaMethod.h"

CallJavaMethod *CallJavaMethod::m_Instance = nullptr;

CallJavaMethod::CallJavaMethod() = default;;

CallJavaMethod::~CallJavaMethod() = default;

CallJavaMethod *CallJavaMethod::getInstance()
{
    if (m_Instance == nullptr) {
        m_Instance = new CallJavaMethod();
    }
    return m_Instance;
}

#include <jni.h>
#include <mutex>

extern "C" {
JavaVM *g_jniJVM = nullptr;
jclass g_jniCls = {};
std::string g_className = {};
std::mutex g_lock;
}

int jvmAttach()
{
    JNIEnv *myNewEnv;
    if (nullptr == g_jniJVM) {
        LOGE("g_jniJVM == NULL");
        return -1;
    }
    int attack = 0;
    JavaVMAttachArgs jvmArgs = {JNI_VERSION_1_6, __FUNCTION__, nullptr};
    int env = g_jniJVM->GetEnv((void **) &myNewEnv, JNI_VERSION_1_6);
    if (JNI_EDETACHED == env) {
        LOGD("callback_handler:failed to get JNI environment assuming native thread");
        env = g_jniJVM->AttachCurrentThread(&myNewEnv, &jvmArgs);
        if (env < 0) {
            LOGE("callback_handler: failed to attach current thread");
            return -2;
        }
        attack = 1;
    }
    return attack;
}

void
CallJavaMethod::callback(const std::string &method, int action, const char *content, bool statics)
{
    int attack = jvmAttach();
    if (attack < 0) {
        LOGE("failed to attach to java vm.");
        return;
    }
    const char *clzz = g_className.c_str();
    std::string cname = g_className;
    replace(cname.begin(), cname.end(), '.', '/');
    JNIEnv *env;
    if (0 != g_jniJVM->GetEnv((void **) &env, JNI_VERSION_1_6)) {
        LOGE("GetEnv fail with JNI_VERSION_1_6, '%s'.", clzz);
        return;
    }
    if (env == nullptr) {
        LOGE("env of class '%s' is NULL.", clzz);
        return;
    }
    LOGI("calling java class %s(%s), method: %s, action = %d, content = %s.",
         clzz, cname.c_str(), method.c_str(), action, content);
    jclass cls = env->FindClass(cname.c_str());
    g_jniCls = (jclass) env->NewGlobalRef(cls);
    jstring msg = env->NewStringUTF(content);
    constexpr const char *mthTag = "(ILjava/lang/String;)V";
    if (statics) {
        jmethodID jmID = env->GetStaticMethodID(g_jniCls, method.c_str(), mthTag);
        env->CallStaticVoidMethod(g_jniCls, jmID, action, msg);
    } else {
        jmethodID jmID = env->GetMethodID(g_jniCls, method.c_str(), mthTag);
        jobject obj = env->AllocObject(g_jniCls);
        env->CallVoidMethod(obj, jmID, action, msg);
        env->DeleteLocalRef(obj);
    }
    env->DeleteLocalRef(msg);
    if (attack == 1)
        g_jniJVM->DetachCurrentThread();
}

void CallJavaMethod::callStaticMethod(const std::string &method, int action, const char *content)
{
    std::lock_guard<std::mutex> lock(g_lock);
    callback(method, action, content, true);
}

void CallJavaMethod::callNonstaticMethod(const std::string &method, int action, const char *content)
{
    std::lock_guard<std::mutex> lock(g_lock);
    callback(method, action, content, false);
}
