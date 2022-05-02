#include <mutex>
#include <string>
#include <thread>
#include <queue>
#include <future>
#ifndef LOG_TAG
#define LOG_TAG "jniComm"
#endif
#include <Utils/logging.h>
#include <time/TimeStamp.h>
#include <Scadup/Scadup.h>
#include <message/Message.h>
#include <network/KcpEmulator.h>
#include "../jni/jniInc.h"
#include "texture/TextureView.h"
#include "callback/JavaFuncCalls.h"

extern JavaVM *g_jniJVM;
extern std::string g_className;
extern std::string Jstring2Cstring(JNIEnv *env, jstring jstr);
extern void SetTextView(JNIEnv *env, jclass thiz, const std::string& viewId, const std::string& text);
extern void SetActivityViewText(JNIEnv *env, int viewId, const char* text);

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

JNIEXPORT jobject CPP_FUNC_CALL(getMessage)(JNIEnv *env, jobject, jobject clazz)
{
    Messaging receiving = Message::instance().getMessage();
    if (!receiving.message.empty()) {
        jclass objectClass = env->FindClass("com/tsymiar/devidroid/data/Receiver");
        jfieldID value = (env)->GetFieldID(objectClass, "message", "Ljava/lang/String;");
        jfieldID key = (env)->GetFieldID(objectClass, "receiver", "I");
        env->SetObjectField(clazz, value, env->NewStringUTF(receiving.message.c_str()));
        env->SetIntField(clazz, key, (int) receiving.massager);
        LOGI("message pop %d %s", receiving.massager, receiving.message.c_str());
        return clazz;
    } else {
        return nullptr;
    }
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

struct PubSubParam {
    std::string addr;
    int port{};
    std::string topic;
    Scadup::RECVCALLBACK hook{};
    JNIEnv env{};
    jclass clz{};
    std::string view;
    int id{};
} g_pubSubParam;

void RecvHook(const Scadup::Message& msg) {
    std::string message = "header:\t[" + std::string(msg.header.topic)
                          + "]\npayload:\t[" + msg.payload.status
                          + "]\t[" + msg.payload.content + "].";
    // Scadup::G_MethodValue[msg.header.tag]
    Message::instance().setMessage(message, MESSAGE);
    // SetActivityViewText(&g_pubSubParam.env, g_pubSubParam.id, msg.data.body);
}

JNIEXPORT jint CPP_FUNC_CALL(StartSubscribe)(JNIEnv *env, jclass clz , jstring addr, jint port, jstring topic, jstring viewId, jint id) {
    jint status = -1;
    std::string address = Jstring2Cstring(env, addr);
    g_pubSubParam.addr = address;
    const std::string msg = Jstring2Cstring(env, topic);
    g_pubSubParam.topic = msg;
    g_pubSubParam.port = port;
    g_pubSubParam.hook = RecvHook;
    g_pubSubParam.env = *env;
    g_pubSubParam.clz = clz;
    const std::string view = Jstring2Cstring(env, viewId);
    g_pubSubParam.view = view;
    g_pubSubParam.id = id;
    std::thread th(
            [&status](const PubSubParam& param) -> void {
                Scadup Scadup;
                Scadup.Initialize(param.addr.c_str(), param.port);
                status = Scadup.Subscriber(param.topic, param.hook);
                char content[256];
                memset(content, 0, 256);
                sprintf(content, "message from %s:%d, topic = '%s', hook = %p, status = %d",
                        param.addr.c_str(), param.port, param.topic.c_str(),param.hook, status);
                Message::instance().setMessage(content, SUBSCRIBER);
                // SetTextView(&param.env, param.clz, param.view, content);
            }, g_pubSubParam);
    if (th.joinable())
        th.detach();
    return status;
}

JNIEXPORT void CPP_FUNC_CALL(QuitSubscribe)(JNIEnv *, jclass)
{
    Scadup::GetInstance().exit();
}

JNIEXPORT void CPP_FUNC_CALL(Publish)(JNIEnv *env, jclass , jstring topic, jstring payload) {
    if (g_pubSubParam.addr.empty() || g_pubSubParam.port == 0) {
        LOGI("g_pubSubParam: addr is null or port == 0.");
        return;
    }
    std::string topicParam = Jstring2Cstring(env, topic);
    std::string payloadParam = Jstring2Cstring(env, payload);
    Scadup Scadup;
    Scadup.Initialize(g_pubSubParam.addr.c_str(), g_pubSubParam.port);
    ssize_t stat = Scadup.Publisher(topicParam, payloadParam);
    if (stat < 0) {
        Message::instance().setMessage("Message Publisher failed!", TOAST);
    }
    LOGI("Publish(%ld) to [%s:%d]: message: [%s][%s].", stat,
         g_pubSubParam.addr.c_str(), g_pubSubParam.port,
         topicParam.c_str(), payloadParam.c_str());
}

int callback(const char *c, int i)
{
    LOGD("JavaFuncCalls::Register c = %s, a = %d.", c, i);
    return i;
}

JNIEXPORT void
CPP_FUNC_CALL(callJavaMethod)(JNIEnv *env, jclass, jstring method, jint action, jstring content,
                              jboolean statics)
{
    JavaFuncCalls::GetInstance().CallBack(Jstring2Cstring(env, method),
                                                  static_cast<int>(action),
                                                 Jstring2Cstring(env, content).c_str(),
                                                  statics);
    JavaFuncCalls::CALLBACK call = callback;
    int val = JavaFuncCalls::GetInstance().Register(const_cast<char *>("aaa"), call);
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
    ANativeWindow *window = initGLSurface();
    readGLFile(filename);
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
        case 3:
            Unlock();
            break;
        default:
            Message::instance().setMessage("Rendering initialize fail", TOAST);
            return;
    }
}

JNIEXPORT jlong JNICALL CPP_FUNC_TIME(getAbsoluteTimestamp)(JNIEnv *, jclass)
{
    return TimeStamp::AbsoluteTime();
}

JNIEXPORT jlong JNICALL CPP_FUNC_TIME(getBootTimestamp)(JNIEnv *, jclass)
{
    return TimeStamp::BootTime();
}

#include <unistd.h>
#include <iostream>
#include <files/Pcm2Wav.h>
#include <network/UdpSocket.h>
#include <network/TcpSocket.h>

// #include <template/Clazz1.h>
// #include <template/Clazz2.h>
static int g_msgLen;

JNIEXPORT jint JNICALL
CPP_FUNC_FILE(convertAudioFiles)(JNIEnv *env, jclass, jstring from, jstring save)
{
    return convertAudioFiles(Jstring2Cstring(env, from).c_str(),
                             Jstring2Cstring(env, save).c_str());
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(sendUdpData)(JNIEnv *env, jclass,
                                                     jstring text, jint len) {
    std::string txt = Jstring2Cstring(env, text);
    const char *tx = txt.c_str();
    std::string message;
    message = "text = [" + txt + "](" + std::to_string(len) + ")";
    Message::instance().setMessage(message, UDP_CLIENT);
    LOGI("%s", message.c_str());
    g_msgLen = len;
    auto *sock = new UdpSocket("127.0.0.1", 8899);
    sock->Sender(tx, (unsigned int) len + 1);
    delete sock;
/*
    auto *clz1 = new Clazz1();
    clz1->setBase<Clazz1>("AAA", 3);
    auto *clz2 = new Clazz2();
    clz2->setBase<Clazz2>("22", 2);
*/
    return 0;
}

void callback(char* data)
{
    if (data[0] != '\0') {
        Message::instance().setMessage(data, UDP_SERVER);
    }
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startUdpServer)(JNIEnv *, jclass)
{
    std::thread th(
            []() -> void {
                int total = g_msgLen + (int)sizeof(NetProtocol);
                char msg[total];
                auto *sock = new UdpSocket();
                int size;
                do {
                    size = sock->Receiver(msg, total, callback);
                    std::string message = "UdpSocket Receiver bind success";
                    Message::instance().setMessage(message, TOAST);
                    usleep(10000);
                } while (size != 0);
                delete sock;
            }
    );
    if (th.joinable())
        th.detach();
    return 0;
}

int tcp_callback(uint8_t *data, size_t size)
{
    Statics::printBuffer((char*)data, size);
    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startTcpServer)(JNIEnv *, jclass, jint port)
{
    std::thread th(
            [](int port) -> void {
                TcpSocket tcp;
                tcp.RegisterCallback(tcp_callback);
                tcp.Start(port);
            }, port);
    if (th.joinable())
        th.detach();
    return 0;
}

JNIEXPORT void JNICALL CPP_FUNC_NETWORK(KcpRun)(JNIEnv *, jclass)
{
    std::thread th([&]()-> void {
        static int index = 0;
        KcpEmulator emulator;
        emulator.KcpRun(index);
        LOGI("kcp emulator run in mode: %d.", index);
        if (index > 2)
            index = 0;
        index++;
    });
    if (th.joinable())
        th.detach();
}
