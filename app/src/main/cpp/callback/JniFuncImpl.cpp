#ifndef LOG_TAG
#define LOG_TAG "JniFuncImpl"
#endif

#include <jni.h>
#include <mutex>
#include <algorithm>
#include <thread>
#include <utils/logging.h>

#ifdef __cplusplus
extern "C" {
#endif
jclass g_jniCls = {};
JavaVM *g_jniJVM = nullptr;
std::string g_className = {};
#ifdef __cplusplus
}
#endif

static jint JNI_RESULT = -1;

int JVM_Attach()
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

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void * /*reserved*/)
{
    typedef union {
        JNIEnv *env;
        void *rsv;
    } UnionJNIEnvToVoid;
    UnionJNIEnvToVoid envToVoid;
    LOGI("Media Tag: JNI OnLoad\n");

#ifdef JNI_VERSION_1_6
    if (JNI_RESULT == -1 && vm->GetEnv(&envToVoid.rsv, JNI_VERSION_1_6) == JNI_OK) {
        LOGI("JNI_OnLoad: JNI_VERSION_1_6\n");
        JNI_RESULT = JNI_VERSION_1_6;
    }
#endif
#ifdef JNI_VERSION_1_4
    if (JNI_RESULT == -1 && vm->GetEnv(&envToVoid.rsv, JNI_VERSION_1_4) == JNI_OK) {
        LOGI("JNI_OnLoad: JNI_VERSION_1_4\n");
        JNI_RESULT = JNI_VERSION_1_4;
    }
#endif
#ifdef JNI_VERSION_1_2
    if (JNI_RESULT == -1 && vm->GetEnv(&envToVoid.rsv, JNI_VERSION_1_2) == JNI_OK) {
        LOGI("JNI_OnLoad: JNI_VERSION_1_2\n");
        JNI_RESULT = JNI_VERSION_1_2;
    }
#endif
    return JNI_RESULT;
}

jstring Cstring2Jstring(JNIEnv *env, const char *pat)
{
    jclass clz = (env)->FindClass("java/lang/String");
    jmethodID jmId = (env)->GetMethodID(clz, "<init>", "([BLjava/lang/String;)V");
    jbyteArray bytes = (env)->NewByteArray(strlen(pat));
    (env)->SetByteArrayRegion(bytes, 0, strlen(pat), (jbyte *) pat);
    jstring encoding = (env)->NewStringUTF("utf-8");
    return (jstring) (env)->NewObject(clz, jmId, bytes, encoding);
}

std::string Jstring2Cstring(JNIEnv *env, jstring jstr)
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

jstring GetPackageName(JNIEnv *env)
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

void CallBackJavaMethod(const std::string &method, int action, const char *content, bool statics)
{
    int attack = JVM_Attach();
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

void ViewSetText(JNIEnv *env, jclass clz, int viewId, const char* text)
{
    if (viewId == 0 || g_jniJVM == nullptr) {
        LOGE("one of these invalid: viewId = %d, jniVm = %p", viewId, g_jniJVM);
        return;
    }
    g_jniJVM->AttachCurrentThread(&env, nullptr);
    jstring msg = env->NewStringUTF(text);
    std::string main_activity = "com/tsymiar/devidroid/activity/MainActivity";
    jclass activity = env->FindClass(main_activity.c_str());
    if (activity != nullptr) {
        g_jniCls = (jclass) env->NewGlobalRef(activity);
        jmethodID showText = env->GetMethodID(
                g_jniCls,
                "showText",
                "(Ljava/lang/String;)V");
        jobject alloc = env->AllocObject(g_jniCls);
        env->CallVoidMethod(alloc, showText, msg);
    } else {
        jmethodID actJmId = (env)->GetMethodID(g_jniCls, "findViewById", "(I)Landroid/view/View;");
        jobject widget = env->CallStaticObjectMethod(g_jniCls, actJmId, actJmId, viewId);
        jclass textClz = env->GetObjectClass(widget);
        jmethodID jmId = (env)->GetMethodID(textClz, "setText", "(Ljava/lang/CharSequence;)V");
        jobject textView = env->AllocObject(textClz);
        env->CallVoidMethod(textView, jmId, msg);
    }
    env->DeleteLocalRef(msg);
    g_jniJVM->DetachCurrentThread();
    LOGI("View.setText: %d, %s", viewId, text);
}
