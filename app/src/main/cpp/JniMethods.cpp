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
#include <files/FileUtils.h>
#include <network/KcpEmulator.h>
#include <gles/EglGpuRender.h>
#include <gles/CpuTextureView.h>
#include "../jni/jniInc.h"
#include "callback/JavaFuncCalls.h"

extern JavaVM *g_jniJVM;
extern std::string g_className;
extern std::string Jstring2Cstring(JNIEnv *env, jstring jstr);
extern void SetTextView(JNIEnv *env, jclass thiz, const std::string& viewId, const std::string& text);
extern void SetActivityViewText(JNIEnv *env, int viewId, const char* text);
namespace {
    int g_height = -1;
    int g_width = -1;
    std::string g_filename;
}

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
        jstring msg = env->NewStringUTF(receiving.message.c_str());
        env->SetObjectField(clazz, value, msg);
        env->SetIntField(clazz, key, (int) receiving.massager);
        LOGI("message pop [%d], %s", receiving.massager, receiving.message.c_str());
        env->DeleteLocalRef(msg);
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
    Message::instance().setMessage(message, MESSAGE);
    // SetActivityViewText(&g_pubSubParam.env, g_pubSubParam.id, msg.payload.content);
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

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(setupSurfaceView)(JNIEnv *env, jclass, jobject texture)
{
    if (CpuTextureView::setupSurfaceView(env, texture) <= 0) {
        LOGI("load Surface fail");
        return;
    }
}

JNIEXPORT void JNICALL CPP_FUNC_VIEW(unloadSurfaceView)(JNIEnv *env, jclass)
{
    CpuTextureView::releaseSurfaceView(env);
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(setRenderSize)(JNIEnv *, jclass, jint height, jint width)
{
    g_height = height;
    g_width = width;
    EglGpuRender::SetWindowSize(height, width);
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(setLocalFile)(JNIEnv *env, jclass, jstring file)
{
    const char *filename = env->GetStringUTFChars(file, JNI_FALSE);
    g_filename = filename;
    env->ReleaseStringUTFChars(file, filename);
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateEglSurface)(JNIEnv *env, jclass, jobject texture)
{
    if (CpuTextureView::setupSurfaceView(env, texture) > 0) {
        LOGI("loaded Surface class");
    }
    ANativeWindow *window = EglGpuRender::OpenGLSurface();
    if (window != nullptr) {
        EglGpuRender::MakeGLTexture();
        extern EGL2 EGL2;
        LOGD("OpenGL rendering initialized wxh(%d, %d)", EGL2.width, EGL2.height);
        FileUtils::ReadBinaryFile(g_filename, EGL2.width * EGL2.height, EglGpuRender::RenderSurface);
        EglGpuRender::CloseGLSurface();
    } else {
        LOGE("native window is null while [updateTextureFile]");
    }
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateEglTexture)(JNIEnv *env, jclass, jobject texture)
{
    if (CpuTextureView::setupSurfaceView(env, texture) > 0) {
        LOGI("loaded Surface class");
    }
    ANativeWindow *window = EglGpuRender::OpenGLSurface();
    if (window != nullptr) {
        LOGD("OpenGL rendering initialized");
        EglGpuRender::DrawRGBTexture(g_filename.c_str());
        EglGpuRender::CloseGLSurface();
    } else {
        LOGE("native window is null while [updateTextureFile]");
    }
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateCpuTexture)(JNIEnv *env, jclass, jobject texture, jint item) {
    switch (item) {
        case 0:
            LOGD("No-implementation");
            return;
        case 3: {
            if (CpuTextureView::setupSurfaceView(env, texture) > 0) {
                LOGI("loaded Surface class");
            }
            long size = 0;
            unsigned char *content = FileUtils::GetFileContentNeedFree(g_filename.c_str(), size);
            LOGD("CPU rendering initialized [%d]", size);
            int height = -1;
            int width = -1;
            if (content != nullptr) {
                if (size > 0 && size < g_height * g_width) {
                    float rate = (float) (g_height * g_width * 1.) / (float) (size)+ 1;
                    height = (int) (g_height * 1. / rate);
                    width = (int) (g_width * 1. / rate);
                } else {
                    height = g_height;
                    width = g_width;
                }
                CpuTextureView::setDisplaySize(height, width);
                CpuTextureView::drawPicture((uint8_t *) content);
            } else {
                static constexpr uint32_t colors[] = {
                        0x00000000,
                        0x0055aaff,
                        0x5500aaff,
                        0xaaff0055,
                        0xff55aa00,
                        0xaa0055ff,
                        0xffffffff
                };
                static int iteration = 0;
                CpuTextureView::drawRGBColor(
                        colors[iteration++ % (sizeof(colors) / sizeof(*colors))]);
            }
            CpuTextureView::releaseSurfaceView(env);
            delete[] content;
            break;
        }
        case 5:
            LOGD("Disconnect");
            EglGpuRender::CloseGLSurface();
            CpuTextureView::releaseSurfaceView(env);
            break;
        default:
            Message::instance().setMessage("CpuRender initialize fail", TOAST);
            return;
    }
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateCpuSurface)(JNIEnv *env, jclass, jobject texture) {
    if (CpuTextureView::setupSurfaceView(env, texture) > 0) {
        LOGD("OpenGL rendering initialized(%d, %d)", g_height, g_width);
        FileUtils::ReadBinaryFile(g_filename, g_width * g_height, CpuTextureView::drawPicture);
        CpuTextureView::releaseSurfaceView(env);
    } else {
        LOGE("native window is null while [updateCpuVideoFile]");
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
#include <decode/Pcm2Wav.h>
#include <network/UdpSocket.h>
#include <network/TcpSocket.h>
// #include <template/Clazz1.h>
// #include <template/Clazz2.h>

static int g_msgLen = 6;

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
    message = "text(" + std::to_string(len) + ") = [" + txt + "]";
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
                    std::string message = "udp receiver starts";
                    Message::instance().setMessage(message, UDP_SERVER);
                    size = sock->Receiver(msg, total, callback);
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
        char hint[64];
        sprintf(hint, "Kcp emulator finish in mode: %d.", index);
        Message::instance().setMessage(hint, TOAST);
        if (index > 2)
            index = 0;
        index++;
    });
    if (th.joinable())
        th.detach();
}
