#include <mutex>
#include <string>
#include <thread>
#include <chrono>
#include <queue>
#include <future>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#ifndef LOG_TAG
#define LOG_TAG "jniComm"
#endif
#include <sstream>
#include <utils/logging.h>
#include <time/TimeStamp.h>
#include <common/Scadup.h>
#include <message/Message.h>
#include <socket/KcpSocket.h>
#include <socket/TcpSocket.h>
#include <socket/FileMsgSocket.h>
#include <display/gles/EglShader.h>
#include <display/gles/EglTexture.h>
#include <display/gles/EglGpuRender.h>
#include <display/cpu/CpuRenderView.h>
#include <utils/FileUtils.h>
#include <bitmap/bitmap.h>
#include "../jni/jniInc.h"
#include "callback/JavaFuncCalls.h"
#include "utils/statics.h"

extern JavaVM* g_jniJVM;
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
        jclass objectClass = env->FindClass("com/tsymiar/device2device/entity/Receiver");
        jfieldID value = (env)->GetFieldID(objectClass, "message", "Ljava/lang/String;");
        jfieldID key = (env)->GetFieldID(objectClass, "receiver", "I");
        jstring msg = env->NewStringUTF(receiving.message.c_str());
        if (msg != nullptr) {
            env->SetObjectField(clazz, value, msg);
            env->SetIntField(clazz, key, (int)receiving.massager);
            LOGI("message pop type=%d, value=%s", receiving.massager,
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
    unsigned char stamp[len * 3 + 1];
    for (size_t i = 0; i < len; i++) {
        sprintf(reinterpret_cast<char*>(stamp + i * 3), "%02x ", byte[i]);
    }
    LOGI("time hex = %s", stamp);
    uint64_t value =
        (byte[8] & 0xff)
        | (byte[9] << 8 & 0xff00)
        | (byte[10] << 16 & 0xff0000)
        | (byte[11] << 24 & 0xff000000)
        | ((uint64_t)byte[12] << 32 & 0xff00000000)
        | ((uint64_t)byte[13] << 40 & 0xff0000000000)
        | ((uint64_t)byte[14] << 48 & 0xff000000000000)
        | ((uint64_t)byte[15] << 56 & 0xff00000000000000);
    env->ReleaseByteArrayElements(time, reinterpret_cast<jbyte*>(byte), 0);
    return value;
}

struct PubSubParam {
    std::string addr;
    int port{};
    uint32_t topic;
    Scadup::RECV_CALLBACK hook{};
} g_pubSubParam;

static std::mutex g_paramMutex;

void RecvHook(const Scadup::Message& msg)
{
    std::stringstream ss;
    ss << std::hex << msg.head.topic;
    std::string message = "Recv topic:\t[0x" + ss.str()
        + "]\tsize=" + std::to_string(msg.head.size) + "\nPayload:\t[" + msg.payload.status
        + "]\t[" + msg.payload.content + "].";
    Message::instance().setMessage(message, MESSAGE);
}

JNIEXPORT jint CPP_FUNC_CALL(StartSubscribe)(JNIEnv* env, jclass, jstring addr, jint port, jstring topic, jstring, jint)
{
    std::string address = Jstring2Cstring(env, addr);
    const std::string topicHex = Jstring2Cstring(env, topic);
    uint32_t iTopic = strtol(topicHex.c_str(), nullptr, 16);

    if (address.empty() || port <= 0) {
        Message::instance().setMessage("Subscribe failed: invalid address or port", TOAST);
        return -1;
    }

    {
        std::lock_guard<std::mutex> lock(g_paramMutex);
        g_pubSubParam.addr = address;
        g_pubSubParam.topic = iTopic;
        g_pubSubParam.port = port;
        g_pubSubParam.hook = RecvHook;
    }

    std::thread task([addr = std::move(address), port, topic = iTopic, hook = RecvHook]() {
        Scadup::Subscriber sub;
        int ret = sub.setup(addr.c_str(), static_cast<unsigned short>(port));
        if (ret < 0) {
            char content[128];
            snprintf(content, sizeof(content), "Subscribe connect fail: %s:%d",
                addr.c_str(), port);
            Message::instance().setMessage(content, TOAST);
            return;
        }

        {
            char content[128];
            snprintf(content, sizeof(content), "Subscribed %s:%d topic 0x%04x",
                addr.c_str(), port, topic);
            Message::instance().setMessage(content, TOAST);
        }

        ret = static_cast<int>(sub.subscribe(topic, hook));

        {
            char content[256];
            snprintf(content, sizeof(content), "Subscribe ended: %s:%d topic 0x%04x status=%d",
                addr.c_str(), port, topic, ret);
            Message::instance().setMessage(content, SUBSCRIBER);
        }
        });
    task.detach();
    return 0;
}

JNIEXPORT void CPP_FUNC_CALL(QuitSubscribe)(JNIEnv*, jclass)
{
    Scadup::Subscriber::exit();
}

JNIEXPORT void CPP_FUNC_CALL(Publish)(JNIEnv* env, jclass, jstring topic, jstring message, jstring addr, jint port)
{
    std::string topicHex = Jstring2Cstring(env, topic);
    std::string payload = Jstring2Cstring(env, message);

    if (topicHex.empty()) {
        Message::instance().setMessage("Publish failed: topic is empty", TOAST);
        return;
    }

    std::string pubAddr;
    int pubPort;
    {
        std::lock_guard<std::mutex> lock(g_paramMutex);
        if (g_pubSubParam.addr.empty() || g_pubSubParam.port == 0) {
            std::string ip = Jstring2Cstring(env, addr);
            if (!ip.empty() && port > 0) {
                g_pubSubParam.addr = ip;
                g_pubSubParam.port = port;
                pubAddr = std::move(ip);
                pubPort = port;
            } else {
                Message::instance().setMessage("Publish failed: no address or port", TOAST);
                return;
            }
        } else {
            pubAddr = g_pubSubParam.addr;
            pubPort = g_pubSubParam.port;
        }
    }

    uint32_t iTopic = strtol(topicHex.c_str(), nullptr, 16);

    std::thread task([pubAddr = std::move(pubAddr), pubPort, iTopic, payload = std::move(payload)]() {
        Scadup::Publisher pub{};
        int ret = pub.setup(pubAddr.c_str(), static_cast<unsigned short>(pubPort));
        if (ret < 0) {
            Message::instance().setMessage(
                "Publish connect failed: " + pubAddr + ":" + std::to_string(pubPort), TOAST);
            return;
        }
        ssize_t stat = pub.publish(iTopic, payload);
        if (stat < 0) {
            Message::instance().setMessage("Publish send failed!", TOAST);
        } else {
            char buf[128];
            snprintf(buf, sizeof(buf), "Published to 0x%x (%zd bytes)", iTopic, stat);
            Message::instance().setMessage(buf, MSG_HINT);
        }
        });
    task.detach();
}

int callback(const char* c, int i)
{
    LOGD("JavaFuncCalls::Register c = %s, a = %d.", c, i);
    return i;
}

JNIEXPORT void CPP_FUNC_CALL(callJavaMethod)(JNIEnv* env, jclass, jstring method, jint action, jstring content, jboolean statics)
{
    JavaFuncCalls::GetInstance().CallBack(Jstring2Cstring(env, method),
        static_cast<int>(action),
        Jstring2Cstring(env, content).c_str(),
        statics);
    JavaFuncCalls::CALLBACK call = callback;
    int val = JavaFuncCalls::GetInstance().Register(const_cast<char*>("aaa"), call);
    LOGI("callback = %p, val = %d.", call, val);
}

JNIEXPORT void JNICALL CPP_FUNC_VIEW(setupSurfaceView)(JNIEnv* env, jclass, jobject texture)
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

JNIEXPORT void JNICALL CPP_FUNC_VIEW(setRenderSize)(JNIEnv*, jclass, jint height, jint width)
{
    g_height = height;
    g_width = width;
    EglGpuRender::SetWindowSize(height, width);
}

JNIEXPORT void JNICALL CPP_FUNC_VIEW(setLocalFile)(JNIEnv* env, jclass, jstring file)
{
    const char* filename = env->GetStringUTFChars(file, JNI_FALSE);
    g_filename = filename;
    env->ReleaseStringUTFChars(file, filename);
}

JNIEXPORT jint JNICALL CPP_FUNC_VIEW(updateEglSurface)(JNIEnv* env, jclass, jobject texture)
{
    if (CpuRenderView::setupSurfaceView(env, texture) > 0) {
        LOGI("loaded Surface class");
    }
    ANativeWindow* window = EglGpuRender::OpenGLSurface();
    if (window != nullptr) {
        GLuint program = EglShader::GetShaderProgram();
        EglTexture::SetTextureBuffers(program);
        extern EGL2 EGL2;
        LOGD("OpenGL rendering initialized WxH = (%d, %d)", EGL2.width, EGL2.height);
        int state = FileUtils::ReadBinaryFile(g_filename, EGL2.width * EGL2.height, EglGpuRender::FrameRender);
        EglGpuRender::CloseGLSurface();
        return state;
    } else {
        LOGE("native window is null while [updateTextureFile]");
        return -1;
    }
}

JNIEXPORT jint JNICALL CPP_FUNC_VIEW(updateEglTexture)(JNIEnv* env, jclass, jobject texture)
{
    if (CpuRenderView::setupSurfaceView(env, texture) > 0) {
        LOGI("loaded Surface class");
    }
    ANativeWindow* window = EglGpuRender::OpenGLSurface();
    if (window != nullptr) {
        LOGD("OpenGL rendering initialized");
        int state = EglGpuRender::DrawRGBTexture(g_filename.c_str());
        EglGpuRender::CloseGLSurface();
        return state;
    } else {
        LOGE("native window is null while [updateTextureFile]");
        return -1;
    }
}

JNIEXPORT jint JNICALL CPP_FUNC_VIEW(updateCpuTexture)(JNIEnv* env, jclass, jobject texture, jint item)
{
    switch (item) {
    case 0:
        LOGD("No-implementation");
        return -1;
    case 3: {
        if (CpuRenderView::setupSurfaceView(env, texture) > 0) {
            LOGI("loaded Surface class");
        }
        long size = 0;
        unsigned char* content = FileUtils::GetFileContentNeedFree(g_filename.c_str(), size);
        LOGD("CPU rendering initialized [%ld]", size);
        if (content != nullptr) {
            BITMAPINFO* info = nullptr;
            uint8_t* data = LoadDIBitmap(g_filename.c_str(), &info);
            if (info == nullptr || data == nullptr) {
                LOGE("LoadDIBitmap failed: info=%p, data=%p", info, data);
                return -2;
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
        return -3;
    }
    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_VIEW(updateCpuSurface)(JNIEnv* env, jclass, jobject texture)
{
    if (CpuRenderView::setupSurfaceView(env, texture) > 0) {
        LOGD("OpenGL rendering initialized(%d, %d)", g_height, g_width);
        int state = FileUtils::ReadBinaryFile(g_filename, g_width * g_height, CpuRenderView::drawSurface);
        CpuRenderView::releaseSurfaceView(env);
        return state;
    } else {
        LOGE("native window is null while [updateCpuVideoFile]");
        return 0;
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
#include <convert/Pcm2Wav.h>
#include <socket/UdpSocket.h>
#include <socket/TcpSocket.h>

static int g_msgLen = 6;
static int g_udpPort = 8899;

// 文件传输
static FileMsgSocket* g_fileMsg = nullptr;
static std::mutex g_fileTransMutex;

JNIEXPORT jint JNICALL CPP_FUNC_FILE(convertAudioFiles)(JNIEnv* env, jclass, jstring from, jstring save)
{
    std::string source = Jstring2Cstring(env, from);
    std::string target = Jstring2Cstring(env, save);
    int stat = convertAudioFiles(source.c_str(), target.c_str());
    if (stat < 0) {
        LOGE("covert audio file from '%s' to '%s' failed", source.c_str(), target.c_str());
    }
    return stat;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(sendUdpData)(JNIEnv* env, jclass, jstring text, jint len)
{
    std::string txt = Jstring2Cstring(env, text);
    const char* tx = txt.c_str();
    std::string message;
    message = "text(" + std::to_string(len) + ") = [" + txt + "]";
    Message::instance().setMessage(message, UDP_CLIENT);
    LOGI("%s", message.c_str());
    g_msgLen = len;
    auto* sock = new UdpSocket("127.0.0.1", g_udpPort);
    if (int ret = sock->Sender(tx, (unsigned int)len + 1) < 0) {
        message = "Sender fail: " + std::to_string(ret);
        Message::instance().setMessage(message, MESSAGE);
    }
    delete sock;
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

// TCP server control state
static std::atomic<TcpSocket*> g_tcpServer{nullptr};
static std::thread*            g_tcpThread = nullptr;
static std::mutex               g_tcpMutex;

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startTcpServer)(JNIEnv*, jclass, jint port)
{
    std::lock_guard<std::mutex> lock(g_tcpMutex);

    // Clean up previous thread handle if server exited naturally
    if (g_tcpThread != nullptr && !g_tcpThread->joinable()) {
        delete g_tcpThread;
        g_tcpThread = nullptr;
    }

    if (g_tcpServer.load() != nullptr) {
        Message::instance().setMessage("TCP server already running", TOAST);
        return -1;
    }

    auto* tcp = new TcpSocket();
    tcp->RegisterCallback(tcp_callback);
    g_tcpServer.store(tcp);

    g_tcpThread = new std::thread([port]() -> void {
        auto* tcp = g_tcpServer.load();
        int ret = tcp->Start(port);
        if (ret != 0) {
            Message::instance().setMessage("TCP Start(" + std::to_string(ret)
                + "): " + std::string(strerror(errno)), TOAST);
        }
        delete tcp;
        g_tcpServer.store(nullptr);
    });

    Message::instance().setMessage(
        "TCP listening port: " + std::to_string(port), TOAST);
    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(stopTcpServer)(JNIEnv*, jclass)
{
    TcpSocket* tcp = g_tcpServer.load();
    if (tcp == nullptr) {
        Message::instance().setMessage("TCP server not running", TOAST);
        return -1;
    }

    // Signal the accept() loop to exit
    tcp->Finish();

    // Self-connect to unblock accept()
    int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (fd >= 0) {
        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = inet_addr("127.0.0.1");
        addr.sin_port = htons(static_cast<uint16_t>(8700));
        ::connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
        ::close(fd);
    }

    // Wait for the server thread to finish
    {
        std::unique_lock<std::mutex> lock(g_tcpMutex);
        if (g_tcpThread != nullptr && g_tcpThread->joinable()) {
            lock.unlock();
            g_tcpThread->join();
            lock.lock();
        }
        delete g_tcpThread;
        g_tcpThread = nullptr;
    }

    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startKcpServer)(JNIEnv*, jclass, jint port)
{
    std::thread th([](int port) -> void {
        KcpSocket kcpSocket{};
        kcpSocket.init(port, false);
        Message::instance().setMessage("Kcp server " + std::to_string(port), KCP_VIEW);
        kcpSocket.startServer();
        }, port);
    if (th.joinable())
        th.detach();
    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startKcpClient)(JNIEnv* env, jclass, jstring ipstr, jint port)
{
    std::string addr = Jstring2Cstring(env, ipstr);
    auto* ipaddr = new unsigned char[addr.size() + 1];
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

// ============ 文件传输 JNI 实现 ============

void FileMsgProgressCallback(uint64_t current, uint64_t total, const std::string& status)
{
    // 限频：最多每 100ms 推送一次进度，避免刷爆消息队列
    static auto lastTime = std::chrono::steady_clock::now();
    auto now = std::chrono::steady_clock::now();
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - lastTime).count();

    // 传输完成/开始等状态消息立即推送
    bool isStatusMsg = (current == 0 || current >= total);

    if (!isStatusMsg && elapsed < 100) {
        return;
    }
    lastTime = now;

    // 进度数据：format "status|current|total" 方便 Java 侧解析
    std::string msg = status + "|" + std::to_string(current) + "|" + std::to_string(total);
    Message::instance().setMessage(msg, FILE_PROGRESS);
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startFileMsgServer)(JNIEnv*, jclass, jint port)
{
    std::lock_guard<std::mutex> lock(g_fileTransMutex);
    if (g_fileMsg != nullptr) {
        LOGW("FileMsg server already running");
        return 0;
    }
    g_fileMsg = new FileMsgSocket();
    g_fileMsg->setProgressCallback(FileMsgProgressCallback);
    int ret = g_fileMsg->startServer((unsigned short)port);
    if (ret < 0) {
        delete g_fileMsg;
        g_fileMsg = nullptr;
        return ret;
    }
    Message::instance().setMessage("FileMsg Server Started on Port " + std::to_string(port), MESSAGE);
    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(connectFileMsgServer)(JNIEnv* env, jclass, jstring ip, jint port)
{
    std::string address = Jstring2Cstring(env, ip);
    std::lock_guard<std::mutex> lock(g_fileTransMutex);
    if (g_fileMsg != nullptr) {
        g_fileMsg->disconnect();
        delete g_fileMsg;
    }
    g_fileMsg = new FileMsgSocket();
    g_fileMsg->setProgressCallback(FileMsgProgressCallback);
    int ret = g_fileMsg->connectToServer(address, (unsigned short)port);
    if (ret < 0) {
        delete g_fileMsg;
        g_fileMsg = nullptr;
        return ret;
    }
    Message::instance().setMessage("Connected to FileMsg server " + address + ":" + std::to_string(port), MESSAGE);
    return 0;
}

JNIEXPORT void JNICALL CPP_FUNC_NETWORK(disconnectFileMsg)(JNIEnv*, jclass)
{
    std::lock_guard<std::mutex> lock(g_fileTransMutex);
    if (g_fileMsg != nullptr) {
        g_fileMsg->disconnect();
        delete g_fileMsg;
        g_fileMsg = nullptr;
        Message::instance().setMessage("FileMsg disconnected", MESSAGE);
    }
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(sendLocalFile)(JNIEnv* env, jclass, jstring filePath)
{
    std::string path = Jstring2Cstring(env, filePath);
    std::lock_guard<std::mutex> lock(g_fileTransMutex);
    if (g_fileMsg == nullptr || !g_fileMsg->isConnected()) {
        LOGE("FileMsg not connected");
        return -1;
    }
    int ret = g_fileMsg->sendLocalFile(path);
    if (ret < 0) {
        Message::instance().setMessage("File send failed", MESSAGE);
        return ret;
    }
    Message::instance().setMessage("File send complete: " + path, MESSAGE);
    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(requestFile)(JNIEnv* env, jclass, jstring ip, jint port, jstring fileName)
{
    std::string address = Jstring2Cstring(env, ip);
    std::string name = Jstring2Cstring(env, fileName);
    std::lock_guard<std::mutex> lock(g_fileTransMutex);
    if (g_fileMsg == nullptr || !g_fileMsg->isConnected()) {
        LOGE("FileMsg not connected");
        return -1;
    }
    return g_fileMsg->requestFile(address, (unsigned short)port, name);
}

JNIEXPORT void JNICALL CPP_FUNC_NETWORK(setFileSavePath)(JNIEnv* env, jclass, jstring path)
{
    std::string savePath = Jstring2Cstring(env, path);
    std::lock_guard<std::mutex> lock(g_fileTransMutex);
    if (g_fileMsg != nullptr) {
        g_fileMsg->setSavePath(savePath);
        LOGI("FileMsg is saving to: %s", savePath.c_str());
    }
}

JNIEXPORT void JNICALL CPP_FUNC_NETWORK(stopFileMsgServer)(JNIEnv*, jclass)
{
    std::lock_guard<std::mutex> lock(g_fileTransMutex);
    if (g_fileMsg != nullptr) {
        g_fileMsg->stopServer();
        delete g_fileMsg;
        g_fileMsg = nullptr;
        Message::instance().setMessage("FileMsg Server Exit", MESSAGE);
    }
}

JNIEXPORT jboolean JNICALL CPP_FUNC_NETWORK(isFileMsgConnected)(JNIEnv*, jclass)
{
    std::lock_guard<std::mutex> lock(g_fileTransMutex);
    if (g_fileMsg != nullptr) {
        return g_fileMsg->isConnected() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}
