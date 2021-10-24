#include <jni.h>
#include <string>
#include <android/native_window.h>
#ifndef LOG_TAG
#define LOG_TAG "jniFunc"
#endif
#include <utils/logging.h>

static jint JNI_RESULT = -1;
extern JavaVM *g_jniJVM;
extern jclass g_jniCls;

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

void ViewSetText(JNIEnv *env, jclass clz, int viewId, const char* text)
{
    if (viewId == 0 || g_jniJVM == nullptr) {
        LOGE("one of these invalid: viewId = %d, jniVm = %p", viewId, g_jniJVM);
        return;
    }
    g_jniJVM->AttachCurrentThread(&env, nullptr);
    jobject activity = env->NewGlobalRef(clz);
    jclass actClz = env->GetObjectClass(activity);
    jmethodID actJmId = (env)->GetMethodID(actClz, "findViewById", "(I)Landroid/view/View;");
    jobject view = env->CallObjectMethod(actClz, actJmId, viewId);
    jclass textView = env->FindClass("android/widget/TextView");
    jmethodID jmId = (env)->GetMethodID(textView, "setText", "(Ljava/lang/CharSequence;)V");
    env->CallVoidMethod(view, jmId, Cstring2Jstring(env, text));
    g_jniJVM->DetachCurrentThread();
    LOGI("View.setText: %d, %s", viewId, text);
}
