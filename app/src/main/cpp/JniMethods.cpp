#include <mutex>
#include <string>
#include <thread>
#ifndef LOG_TAG
#define LOG_TAG "jniComm"
#endif
#include <utils/logging.h>
#include <runtime/TimeStamp.h>
#include <kaics/KaiSocket.h>
#include "../jni/jniInc.h"
#include "texture/TextureView.h"
#include "callback/CallJavaMethod.h"

extern JavaVM *g_jniJVM;
extern std::string g_className;
extern std::string Jstring2Cstring(JNIEnv *env, jstring jstr);
extern void ViewSetText(JNIEnv *env, jclass clz, int viewId = 0, const char* text = nullptr);

JNIEXPORT void CPP_FUNC_CALL(initJvmEnv)(JNIEnv *env, jclass, jstring class_name)
{
    int state = env->GetJavaVM(&g_jniJVM);
    g_className =
            // Jstring2Cstring(env, getPackageName(env))
            // + "." +
            Jstring2Cstring(env, class_name);
    LOGI("class_name = %s, state = %d.", g_className.c_str(), state);
}

#include "utils/statics.h"

JNIEXPORT jstring CPP_FUNC_CALL(stringGetJNI)(
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

JNIEXPORT jlong CPP_FUNC_CALL(timeSetJNI)(JNIEnv *env, jobject, jbyteArray time, jint len)
{
    auto *byte = (unsigned char *) env->GetByteArrayElements(time, nullptr);
    unsigned char stamp[len * 3];
    for (size_t i = 0; i < len; i++) {
        sprintf(reinterpret_cast<char *>(stamp + i * 3), "%02x ", byte[i]);
    }
    LOGI("time hex = %s", stamp);
    uint64_t val =
            (byte[8] & 0xff)
            | (byte[9] << 8 & 0xff00)
            | (byte[10] << 16 & 0xff0000)
            | (byte[11] << 24 & 0xff000000)
            | ((uint64_t) byte[12] << 32 & 0xff00000000)
            | ((uint64_t) byte[13] << 40 & 0xff0000000000)
            | ((uint64_t) byte[14] << 48 & 0xff000000000000)
            | ((uint64_t) byte[15] << 56 & 0xff00000000000000);
    return val;
}

void RecvHook(const KaiSocket::Message& msg)
{
    LOGI("topic '%s' of %s, payload: [%s]-[%s].",
         msg.head.topic,
         KaiSocket::G_KaiRole[msg.head.etag],
         msg.data.stat,
         msg.data.body);
}

struct PubSubParam {
    std::string addr;
    int port;
    std::string topic;
    KaiSocket::RECVCALLBACK hook;
};

JNIEXPORT jint CPP_FUNC_CALL(KaiSubscribe)(JNIEnv *env, jclass clz , jstring addr, jint port, jstring topic, jint viewId) {
    jint status = -1;
    std::string address = Jstring2Cstring(env, addr);
    const std::string msg = Jstring2Cstring(env, topic);
    PubSubParam param = {address, port, msg, RecvHook};
    std::thread th(
            [&status](JNIEnv *env, jclass clz, int id, const PubSubParam &param) -> void {
                KaiSocket kaiSocket;
                kaiSocket.Initialize(param.addr.c_str(), param.port);
                status = kaiSocket.Subscriber(param.topic, param.hook);
                char msg[256];
                memset(msg, 0, 256);
                sprintf(msg, "message from %s:%d, topic = '%s', hook = %p, status = %d",
                        param.addr.c_str(), param.port, param.topic.c_str(),param.hook, status);
                ViewSetText(env, clz, id, msg);
            }, env, clz, viewId, param);
    if (th.joinable())
        th.detach();
    return status;
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
    CallJavaMethod::GetInstance().callMethodBack(Jstring2Cstring(env, method),
                                                  static_cast<int>(action),
                                                 Jstring2Cstring(env, content).c_str(),
                                                  statics);
    CallJavaMethod::CALLBACK call = callback;
    int val = CallJavaMethod::GetInstance().registerCallBack(const_cast<char *>("aaa"), call);
    LOGI("callback = %p, val = %d.", call, val);
}

JNIEXPORT jboolean JNICALL
CPP_FUNC_VIEW(setFileLocate)(JNIEnv *env, jclass, jstring filename)
{
    std::string file_in = Jstring2Cstring(env, filename);
    FILE *fp_in = fopen(file_in.c_str(), "rbe");
    if (nullptr == fp_in) {
        LOGE("open input h264 video file failed, filename [%s]", file_in.c_str());
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
                    0x00000000,
                    0x0055aaff,
                    0x5500aaff,
                    0xaaff0055,
                    0xff55aa00,
                    0xaa0055ff,
                    0xffffffff
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
#include <network/UdpSocket.h>
// #include <template/Clazz1.h>
// #include <template/Clazz2.h>

JNIEXPORT jint JNICALL
CPP_FUNC_FILE(convertAudioFiles)(JNIEnv *env, jclass, jstring from, jstring save)
{
    return convertAudioFiles(Jstring2Cstring(env, from).c_str(),
                             Jstring2Cstring(env, save).c_str());
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(sendUdpData)(JNIEnv *env, jclass,
                                                     jstring text, jint len)
{
    std::string txt = Jstring2Cstring(env, text);
    const char *tx = txt.c_str();
    LOGI("text = %s, %d", tx, len);
    auto *sock = new UdpSocket("127.0.0.1", 8899);
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
                auto *sock = new UdpSocket();
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