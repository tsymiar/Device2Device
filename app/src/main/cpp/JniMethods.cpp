#include <mutex>
#include <string>
#include <thread>
#include <queue>
#include <future>
#ifndef LOG_TAG
#define LOG_TAG "jniComm"
#endif
#include <utils/logging.h>
#include <time/TimeStamp.h>
#include <common/Scadup.h>
#include <message/Message.h>
#include <network/KcpSocket.h>
#include <render/gles/EglShader.h>
#include <render/gles/EglTexture.h>
#include <render/gles/EglGpuRender.h>
#include <render/CpuRenderView.h>
#include <utils/FileUtils.h>
#include <binfile/bitmap.h>
#include "../jni/jniInc.h"
#include "callback/JavaFuncCalls.h"
#include "utils/statics.h"

extern JavaVM * g_jniJVM;
extern std::string g_className;
extern std::string Jstring2Cstring(JNIEnv* env, jstring jstr);
extern void SetTextView(JNIEnv* env, jclass thiz, const std::string& viewId, const std::string& text);
extern void SetActivityViewText(JNIEnv* env, int viewId, const char* text);
namespace {
    int g_height = -1;
    int g_width = -1;
    std::string g_filename;
}

JNIEXPORT void CPP_FUNC_CALL(initJvmEnv)(JNIEnv* env, jclass, jstring class_name)
{
    int state = env->GetJavaVM(&g_jniJVM);
    g_className =
            // Jstring2Cstring(env, getPackageName(env))
            // + "." +
            Jstring2Cstring(env, class_name);
    LOGI("class_name = %s, state = %d.", g_className.c_str(), state);
}

JNIEXPORT jstring CPP_FUNC_CALL(stringGetJNI)(
        JNIEnv* env,
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

JNIEXPORT jobject CPP_FUNC_CALL(getMessage)(JNIEnv* env, jobject, jobject clazz)
{
    Messaging receiving = Message::instance().getMessage();
    if (!receiving.message.empty() && env != nullptr) {
        jclass objectClass = env->FindClass("com/tsymiar/device2device/data/Receiver");
        jfieldID value = (env)->GetFieldID(objectClass, "message", "Ljava/lang/String;");
        jfieldID key = (env)->GetFieldID(objectClass, "receiver", "I");
        jstring msg = env->NewStringUTF(receiving.message.c_str());
        if (msg != nullptr) {
            env->SetObjectField(clazz, value, msg);
            env->SetIntField(clazz, key, (int)receiving.massager);
            LOGI("message pop type: [%d], value: [%s]", receiving.massager,
                 receiving.message.c_str());
            env->DeleteLocalRef(msg);
        }
        return clazz;
    } else {
        return nullptr;
    }
}

JNIEXPORT jlong CPP_FUNC_CALL(timeSetJNI)(JNIEnv* env, jobject, jbyteArray time, jint len)
{
    auto* byte = (unsigned char*)env->GetByteArrayElements(time, nullptr);
    unsigned char stamp[len * 3 + 1]; // 修正数组大小
    for (size_t i = 0; i < len; i++) {
        sprintf(reinterpret_cast<char*>(stamp + i * 3), "%02x ", byte[i]);
    }
    LOGI("time hex = %s", stamp);
    uint64_t val =
            (byte[8] & 0xff)
            | (byte[9] << 8 & 0xff00)
            | (byte[10] << 16 & 0xff0000)
            | (byte[11] << 24 & 0xff000000)
            | ((uint64_t)byte[12] << 32 & 0xff00000000)
            | ((uint64_t)byte[13] << 40 & 0xff0000000000)
            | ((uint64_t)byte[14] << 48 & 0xff000000000000)
            | ((uint64_t)byte[15] << 56 & 0xff00000000000000);
    env->ReleaseByteArrayElements(time, reinterpret_cast<jbyte*>(byte), 0); // 释放资源
    return val;
}

struct PubSubParam {
    std::string addr;
    int port{};
    uint32_t topic;
    Scadup::RECV_CALLBACK hook{};
    JNIEnv env{};
    jclass clz{};
    std::string view;
    int id{};
} g_pubSubParam;

void RecvHook(const Scadup::Message& msg)
{
    std::string message = "header:\t[" + std::to_string(msg.head.topic)
                          + "]\npayload:\t[" + msg.payload.status
                          + "]\t[" + msg.payload.content + "].";
    Message::instance().setMessage(message, MESSAGE);
    // SetActivityViewText(&g_pubSubParam.env, g_pubSubParam.id, msg.payload.content);
}

JNIEXPORT jint CPP_FUNC_CALL(StartSubscribe)(JNIEnv* env, jclass clz, jstring addr, jint port, jstring topic, jstring viewId, jint id)
{
    jint status = -1;
    std::string address = Jstring2Cstring(env, addr);
    g_pubSubParam.addr = address;
    const std::string msg = Jstring2Cstring(env, topic);
    g_pubSubParam.topic = strtol(msg.c_str(), nullptr, 16);
    g_pubSubParam.port = port;
    g_pubSubParam.hook = RecvHook;
    g_pubSubParam.env = *env;
    g_pubSubParam.clz = clz;
    const std::string view = Jstring2Cstring(env, viewId);
    g_pubSubParam.view = view;
    g_pubSubParam.id = id;
    std::thread th(
            [&status](const PubSubParam& param) -> void {
                Scadup::Subscriber sub;
                sub.setup(param.addr.c_str(), param.port);
                status = sub.subscribe(param.topic, param.hook);
                char content[256];
                memset(content, 0, 256);
                sprintf(content, "message of %s:%d, topic: '0x%04x', hook = %p, status = %d",
                        param.addr.c_str(), param.port, param.topic, param.hook, status);
                Message::instance().setMessage(content, SUBSCRIBER);
                // SetTextView(&param.env, param.clz, param.view, content);
            }, std::ref(g_pubSubParam)); // 使用 std::ref 捕获 g_pubSubParam
    if (th.joinable())
        th.detach();
    return status;
}

JNIEXPORT void CPP_FUNC_CALL(QuitSubscribe)(JNIEnv*, jclass)
{
    Scadup::Subscriber::exit();
}

JNIEXPORT void CPP_FUNC_CALL(Publish)(JNIEnv* env, jclass, jstring topic, jstring payload)
{
    if (g_pubSubParam.addr.empty() || g_pubSubParam.port == 0) {
        LOGI("g_pubSubParam: addr is null or port == 0.");
        return;
    }
    uint32_t iTopic = atoi(Jstring2Cstring(env, topic).c_str());
    std::string payloadParam = Jstring2Cstring(env, payload);
    Scadup::Publisher pub{};
    pub.setup(g_pubSubParam.addr.c_str(), g_pubSubParam.port);
    ssize_t stat = pub.publish(iTopic, payloadParam);
    if (stat < 0) {
        Message::instance().setMessage("Message Publisher failed!", TOAST);
    }
    LOGI("Publish(%ld) to [%s:%d]: [topic=0x%04x] message: [%s].", stat,
         g_pubSubParam.addr.c_str(), g_pubSubParam.port,
         iTopic, payloadParam.c_str());
}

int callback(const char* c, int i)
{
    LOGD("JavaFuncCalls::Register c = %s, a = %d.", c, i);
    return i;
}

JNIEXPORT void
CPP_FUNC_CALL(callJavaMethod)(JNIEnv* env, jclass, jstring method, jint action, jstring content,
                              jboolean statics)
{
    JavaFuncCalls::GetInstance().CallBack(Jstring2Cstring(env, method),
                                          static_cast<int>(action),
                                          Jstring2Cstring(env, content).c_str(),
                                          statics);
    JavaFuncCalls::CALLBACK call = callback;
    int val = JavaFuncCalls::GetInstance().Register(const_cast<char*>("aaa"), call);
    LOGI("callback = %p, val = %d.", call, val);
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(setupSurfaceView)(JNIEnv* env, jclass, jobject texture)
{
    if (CpuRenderView::setupSurfaceView(env, texture) <= 0) {
        LOGI("load Surface fail");
        return;
    }
}

JNIEXPORT void JNICALL CPP_FUNC_VIEW(unloadSurfaceView)(JNIEnv* env, jclass)
{
    CpuRenderView::releaseSurfaceView(env);
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(setRenderSize)(JNIEnv*, jclass, jint height, jint width)
{
    g_height = height;
    g_width = width;
    EglGpuRender::SetWindowSize(height, width);
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(setLocalFile)(JNIEnv* env, jclass, jstring file)
{
    const char* filename = env->GetStringUTFChars(file, JNI_FALSE);
    g_filename = filename;
    env->ReleaseStringUTFChars(file, filename);
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateEglSurface)(JNIEnv* env, jclass, jobject texture)
{
    if (CpuRenderView::setupSurfaceView(env, texture) > 0) {
        LOGI("loaded Surface class");
    }
    ANativeWindow* window = EglGpuRender::OpenGLSurface();
    if (window != nullptr) {
        // EglGpuRender::MakeGLTexture();
        GLuint program = EglShader::GetShaderProgram();
        EglTexture::SetTextureBuffers(program);
        extern EGL2 EGL2;
        LOGD("OpenGL rendering initialized WxH = (%d, %d)", EGL2.width, EGL2.height);
        FileUtils::ReadBinaryFile(g_filename, EGL2.width * EGL2.height, EglGpuRender::FrameRender);
        EglGpuRender::CloseGLSurface();
    } else {
        LOGE("native window is null while [updateTextureFile]");
    }
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateEglTexture)(JNIEnv* env, jclass, jobject texture)
{
    if (CpuRenderView::setupSurfaceView(env, texture) > 0) {
        LOGI("loaded Surface class");
    }
    ANativeWindow* window = EglGpuRender::OpenGLSurface();
    if (window != nullptr) {
        LOGD("OpenGL rendering initialized");
        EglGpuRender::DrawRGBTexture(g_filename.c_str());
        EglGpuRender::CloseGLSurface();
    } else {
        LOGE("native window is null while [updateTextureFile]");
    }
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateCpuTexture)(JNIEnv* env, jclass, jobject texture, jint item)
{
    switch (item) {
        case 0:
            LOGD("No-implementation");
            return;
        case 3: {
            if (CpuRenderView::setupSurfaceView(env, texture) > 0) {
                LOGI("loaded Surface class");
            }
            long size = 0;
            unsigned char* content = FileUtils::GetFileContentNeedFree(g_filename.c_str(), size);
            LOGD("CPU rendering initialized [%d]", size);
            if (content != nullptr) {
                BITMAPINFO* info;
                uint8_t* data = LoadDIBitmap(g_filename.c_str(), &info);
                if (info == nullptr) {
                    LOGE("LoadDIBitmap failed");
                    return;

                }
                CpuRenderView::setDisplaySize((int)info->bmiHeader.biHeight,
                                              (int)info->bmiHeader.biWidth);
                CpuRenderView::drawSurface(data);
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
                CpuRenderView::drawRGBColor(
                        colors[iteration++ % (sizeof(colors) / sizeof(*colors))], g_filename.c_str());
            }
            CpuRenderView::releaseSurfaceView(env);
            delete[] content;
            break;
        }
        case 5:
            LOGD("Disconnect");
            EglGpuRender::CloseGLSurface();
            CpuRenderView::releaseSurfaceView(env);
            break;
        default:
            Message::instance().setMessage("CpuRender initialize fail", TOAST);
            return;
    }
}

JNIEXPORT void JNICALL
CPP_FUNC_VIEW(updateCpuSurface)(JNIEnv* env, jclass, jobject texture)
{
    if (CpuRenderView::setupSurfaceView(env, texture) > 0) {
        LOGD("OpenGL rendering initialized(%d, %d)", g_height, g_width);
        FileUtils::ReadBinaryFile(g_filename, g_width * g_height, CpuRenderView::drawSurface);
        CpuRenderView::releaseSurfaceView(env);
    } else {
        LOGE("native window is null while [updateCpuVideoFile]");
    }
}

JNIEXPORT jlong JNICALL CPP_FUNC_TIME(getAbsoluteTimestamp)(JNIEnv*, jclass)
{
    return TimeStamp::AbsoluteTime();
}

JNIEXPORT jlong JNICALL CPP_FUNC_TIME(getBootTimestamp)(JNIEnv*, jclass)
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
static int g_udpPort = 8899;

JNIEXPORT jint JNICALL
CPP_FUNC_FILE(convertAudioFiles)(JNIEnv* env, jclass, jstring from, jstring save)
{
    std::string source = Jstring2Cstring(env, from);
    std::string target = Jstring2Cstring(env, save);
    int stat = convertAudioFiles(source.c_str(), target.c_str());
    if (stat < 0) {
        LOGE("covert audio file from '%s' to '%s' failed", source.c_str(), target.c_str());
    }
    return stat;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(sendUdpData)(JNIEnv* env, jclass,
                                                     jstring text, jint len)
{
    std::string txt = Jstring2Cstring(env, text);
    const char* tx = txt.c_str();
    std::string message;
    message = "text(" + std::to_string(len) + ") = [" + txt + "]";
    Message::instance().setMessage(message, UDP_CLIENT);
    LOGI("%s", message.c_str());
    g_msgLen = len;
    auto* sock = new UdpSocket("127.0.0.1", g_udpPort);
    sock->Sender(tx, (unsigned int)len + 1);
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

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startUdpServer)(JNIEnv*, jclass, jint port)
{
    std::thread th(
            [&]() -> void {
                int total = g_msgLen + (int)sizeof(NetProtocol);
                char msg[total];
                auto* sock = new UdpSocket(port);
                g_udpPort = port;
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

int tcp_callback(uint8_t* data, size_t size)
{
    Statics::printBuffer((char*)data, size);
    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startTcpServer)(JNIEnv*, jclass, jint port)
{
    std::thread th([](int port) -> void {
        TcpSocket tcp;
        tcp.RegisterCallback(tcp_callback);
        tcp.Start(port);
        tcp.Finish();
    }, port);
    if (th.joinable())
        th.detach();
    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startKcpServer)(JNIEnv*, jclass, jint port)
{
    std::thread th([](int port) -> void {
        KcpSocket kcpSocket{};
        kcpSocket.init(port, false);
        Message::instance().setMessage("Kcp server " + std::to_string(port), UPDATE_VIEW);
        kcpSocket.startServer();
    }, port);
    if (th.joinable())
        th.detach();
    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startKcpClient)(JNIEnv* env, jclass, jstring ipstr, jint port)
{
    std::string addr = Jstring2Cstring(env, ipstr);
    auto* ipaddr = new unsigned char[addr.size() +1];
    memset(ipaddr, 0, addr.size() + 1);
    memcpy(ipaddr, addr.c_str(), addr.size());
    std::thread th([](int port, unsigned char* ip) -> void {
        KcpSocket kcpSocket{};
        kcpSocket.init(port, true, (const char*)ip);
        char hint[128];
        sprintf(hint, "Kcp client start %s:%d.", ip, port);
        Message::instance().setMessage(hint, TOAST);
        kcpSocket.startClient();
        delete ip;
    }, port, ipaddr);
    if (th.joinable())
        th.detach();
    return 0;
}
