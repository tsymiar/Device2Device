#ifndef LOG_TAG
#define LOG_TAG "jniComm"
#endif

#include <utils/logger.h>

#include <mutex>
#include <string>
#include <thread>
#include <android/native_window.h>
#include <runtime/TimeStamp.h>

#include "../jni/jniInc.h"
#include "texture/TextureView.h"
#include "callback/CallJavaMethod.h"

static jint JNI_RESULT = -1;

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

jstring cstring2jstring(JNIEnv *env, const char *pat)
{
    jclass clz = (env)->FindClass("java/lang/String");
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

#include "utils/statics.h"

JNIEXPORT jstring CPP_FUNC_CALL(stringFromJNI)(
        JNIEnv *env,
        jobject /* this */)
{
    std::string hello = "C++ string of JNI!";
    char text[16] = {
            0x1a, 0x13, 0x00, 0x07,
            static_cast<char>(0xcc), static_cast<char>(0xff),
            static_cast<char>(0xe0), static_cast<char>(0x88)
    };
    Statics::printBuffer(text, 32);
    return env->NewStringUTF(hello.c_str());
}

int callback(const char *c, int i)
{
    LOGE("param1 = %s, param2 = %d.", c, i);
    return i;
}

JNIEXPORT void
CPP_FUNC_CALL(callJavaMethod)(JNIEnv *env, jclass, jstring method, jint action, jstring content,
                              jboolean statics)
{
    CallJavaMethod::getInstance()->callMethodBack(jstring2cstring(env, method),
                                                  static_cast<int>(action),
                                                  jstring2cstring(env, content).c_str(),
                                                  statics);
    CallJavaMethod::CALLBACK call = callback;
    int val = CallJavaMethod::getInstance()->registerCallBack(const_cast<char *>("aaa"), call);
    LOGI("callback = %p, val = %d.", call, val);
}

JNIEXPORT jboolean JNICALL
CPP_FUNC_VIEW(setFileLocate)(JNIEnv *env, jclass, jstring filename)
{
    const char *file_in = jstring2cstring(env, filename).c_str();
    FILE *fp_in = fopen(file_in, "rbe");
    if (nullptr == fp_in) {
        LOGE("open input h264 video file failed, filename [%s]", file_in);
        return (jboolean) JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateEglSurface)(JNIEnv *env, jclass, jobject texture, jstring url)
{
    using namespace TextureView;
    int jvs = loadSurfaceView(env, texture);
    if (jvs > 0) {
        LOGI("loaded Surface class: %x", jvs);
    }
    const char *filename = env->GetStringUTFChars(url, JNI_FALSE);
    ANativeWindow *window = initOpenGL(filename);
    if (window != nullptr) {
        LOGD("OpenGL rendering initialized");
        drawRGBColor(1280, 720);
    } else {
        LOGE("native window = null while initOpenGL.");
    }
    env->ReleaseStringUTFChars(url, filename);
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateSurfaceView)(JNIEnv *env, jclass, jobject texture, jint item)
{
    using namespace TextureView;
    if (item != 1 && item != 2) {
        int jvs = loadSurfaceView(env, texture);
        if (jvs > 0) {
            LOGI("loaded Surface class: %x", jvs);
        }
    }
    switch (item) {
        case 0:
            // No implementation selected
            LOGD("De-initialized");
            return;
        case 1: {
            LOGD("CPU rendering initialized");
            static int iteration = 0;
            static constexpr uint32_t colors[] = {
                    0x88bf360c,
                    0xcc3e2723,
                    0xaaffdd60,
                    0x6664dd17,
                    0x880277ac,
                    0xff880e4f
            };
            drawRGBColor(colors[iteration++ % (sizeof(colors) / sizeof(*colors))]);
            break;
        }
        default:
            LOGE("Rendering initialize fail");
            return;
    }
}

JNIEXPORT jlong JNICALL CPP_FUNC_TIME(getAbsoluteTimestamp)(JNIEnv *, jclass)
{
    return TimeStamp::get()->AbsoluteTime();
}

JNIEXPORT jlong JNICALL CPP_FUNC_TIME(getBootTimestamp)(JNIEnv *, jclass)
{
    return TimeStamp::get()->BootTime();
}

#include <unistd.h>
#include <file/Pcm2Wav.h>
#include <network/SocketNet.h>
// #include <template/Clazz1.h>
// #include <template/Clazz2.h>

JNIEXPORT jint JNICALL
CPP_FUNC_FILE(convertAudioFiles)(JNIEnv *env, jclass, jstring from, jstring save)
{
    return convertAudioFiles(jstring2cstring(env, from).c_str(),
                             jstring2cstring(env, save).c_str());
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(sendUdpData)(JNIEnv *env, jclass,
                                                     jstring text, jint len)
{
    std::string txt = jstring2cstring(env, text);
    const char *tx = txt.c_str();
    LOGI("text = %s, %d", tx, len);
    auto *sock = new SocketNet("127.0.0.1", 9999);
    sock->Sender(tx, (unsigned int) len);
/*
    auto *clz1 = new Clazz1();
    clz1->setBase<Clazz1>("AAA", 3);
    auto *clz2 = new Clazz2();
    clz2->setBase<Clazz2>("22", 2);
*/
    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startServer)(JNIEnv *, jclass)
{
    std::thread th(
            []() -> void {
                auto *sock = new SocketNet();
                char buff[36];
                while (sock->Receiver(buff, 36) != 0) {
                    usleep(10000);
                }
            }
    );
    if (th.joinable())
        th.detach();
    return 0;
}