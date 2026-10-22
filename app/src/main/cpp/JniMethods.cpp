#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <chrono>
#include <cstdlib>
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
#include <socket/LanMessenger.h>
#include <display/gles/EglShader.h>
#include <display/gles/EglTexture.h>
#include <display/gles/EglGpuRender.h>
#include <display/cpu/CpuRenderView.h>
#include <utils/FileUtils.h>
#include <bitmap/bitmap.h>
#include "../jni/jniInc.h"
#include "callback/JavaFuncCalls.h"

extern JavaVM* g_jniJVM;
extern std::string g_className;
extern std::string Jstring2Cstring(JNIEnv* env, jstring jstr);
extern void SetTextView(JNIEnv* env, jclass thiz, const std::string& viewId, const std::string& text);
extern void SetActivityViewText(JNIEnv* env, int viewId, const char* text);

/** EGL 状态（display/context/surface/program）是 EglGpuRender.cpp 里那一份全局状态 */
extern EGL2 EGL2;

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

/**
 * 订阅是否还在跑。
 *
 * 悬浮窗上连点几次 Confirm 就会来几个 StartSubscribe，几路订阅线程共用
 * 一个 Subscriber 的静态状态（m_exit / 线程池 / socket），
 * 互相把对方的 socket 关掉、把对方的池子停掉 —— 表现就是「订阅两次必崩」。
 */
static std::atomic<bool> g_subscribing{false};

namespace {
/** 任何出口（正常结束 / 连不上 / 中途 return）都把订阅标志复位 */
struct SubscribeGuard {
    ~SubscribeGuard() { g_subscribing.store(false); }
};
}

void RecvHook(const Scadup::Message& msg)
{
    // payload.length 才是正文长度：零长度正文时 content 是空指针，按 C 字符串拼会崩；
    // 正文里允许嵌 NUL，按 strlen 读又会短一截
    const Scadup::Message::Payload& pl = msg.payload;
    const std::string body = (pl.content != nullptr && pl.length > 0)
            ? std::string(pl.content, pl.length) : std::string();
    std::stringstream ss;
    ss << std::hex << msg.head.topic;
    std::string message = "Recv topic:\t[0x" + ss.str() + "]\tsize=" + std::to_string(body.size())
        + "\nPayload:\t[" + pl.status + "]\t[ " + body + " ].";
    Message::instance().setMessage(message, MSG_STAT);
}

/**
 * 订阅线程的任务体。
 *
 * 工程按 -std=c++11 编译，写不了 `[addr = std::move(address), ...]` 这种 lambda
 * 初始化捕获（那是 C++14 扩展，会报 -Wc++14-extensions）。所以用「普通函数 + std::thread
 * 变参构造」：线程构造时把实参逐个 move / 拷贝到线程内部存储再回调，detach 之后
 * 也不依赖调用栈。
 */
static void SubscribeTask(std::string addr, int port, uint32_t topic, Scadup::RECV_CALLBACK hook)
{
    SubscribeGuard guard;
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
        char content[160];
        snprintf(content, sizeof(content), "Subscribed %s:%d topic 0x%04x (one-to-one id 0x%llx)",
            addr.c_str(), port, topic, static_cast<unsigned long long>(sub.dstId()));
        Message::instance().setMessage(content, TOAST);
    }

    ret = static_cast<int>(sub.subscribe(topic, hook));

    char content[256];
    snprintf(content, sizeof(content), "Subscribe ended: %s:%d topic 0x%04x status=%d",
        addr.c_str(), port, topic, ret);
    Message::instance().setMessage(content, SUBSCRIBER);
}

/**
 * 解析十六进制 topic；空串或解析不出来返回 false（topic 0 是合法值，
 * 但不能由一串乱码悄悄变成 0，否则订阅/发布到错误的 topic 上还看不出原因）。
 */
static bool parseTopicHex(const std::string& hex, uint32_t& topic)
{
    // 先去首尾空白：悬浮窗里粘贴过来的值常带空格
    const size_t begin = hex.find_first_not_of(" \t\r\n");
    if (begin == std::string::npos) {
        return false;
    }
    const size_t last = hex.find_last_not_of(" \t\r\n");
    std::string text = hex.substr(begin, last - begin + 1);

    // 认 0x / 0X 前缀：界面默认值是「0x1234」，而 strtol(…, 16) 只吃掉前导 0、从 'x' 处停下，
    // 整串就成了 0 —— 与手打的「1234」对不上，看着像「连上了但收不到」
    if (text.size() > 2 && text[0] == '0' && (text[1] == 'x' || text[1] == 'X')) {
        text = text.substr(2);
    }
    if (text.empty()) {
        return false;
    }

    char* end = nullptr;
    const long value = strtol(text.c_str(), &end, 16);
    // 整串都必须是十六进制数字：以前「12zz」会被当成 0x12 悄悄订上去
    if (end == text.c_str() || *end != '\0' || value < 0 || value > 0xFFFFFFFFL) {
        return false;
    }
    topic = static_cast<uint32_t>(value);
    return true;
}

/**
 * 发布线程的任务体，同样是为避开 C++14 的 lambda 初始化捕获。
 *
 * Publisher 连接是持久的：整个进程共用一个实例（串行化访问），发布之间不再重复
 * TCP 握手。上一次发布若把连接发坏了（broker 重启等），先重连再重试一次。
 */
static void PublishTask(std::string pubAddr, int pubPort, uint32_t topic, std::string payload, uint64_t dst)
{
    static std::mutex s_publishMutex;
    static Scadup::Publisher s_publisher;
    std::lock_guard<std::mutex> lock(s_publishMutex);

    // 空正文 broker 收不到：Publisher::publish 对空串直接返回 0，什么都不发
    if (payload.empty()) {
        Message::instance().setMessage("Publish failed: payload is empty", TOAST);
        return;
    }

    int ret = s_publisher.setup(pubAddr.c_str(), static_cast<unsigned short>(pubPort));
    if (ret < 0) {
        Message::instance().setMessage(
            "Publish connect failed: " + pubAddr + ":" + std::to_string(pubPort), TOAST);
        return;
    }
    int stat = s_publisher.publish(topic, payload, dst);
    if (stat < 0) {
        // 连接可能已被 broker 断开：重建连接后重试一次
        s_publisher.quit();
        ret = s_publisher.setup(pubAddr.c_str(), static_cast<unsigned short>(pubPort));
        if (ret == 0)
            stat = s_publisher.publish(topic, payload, dst);
    }
    if (stat < 0) {
        Message::instance().setMessage("Publish send failed!", TOAST);
        return;
    }
    char buf[192];
    const int delivered = s_publisher.delivered();
    if (delivered < 0) {
        snprintf(buf, sizeof(buf), "Published to 0x%x (%d bytes, no delivery report)",
            topic, stat);
    } else if (delivered == 0) {
        snprintf(buf, sizeof(buf),
            "Published to 0x%x (%d bytes, 0 subscriber: nobody is listening)", topic, stat);
    } else {
        snprintf(buf, sizeof(buf), "Published to 0x%x (%d bytes, %d subscriber(s))",
            topic, stat, delivered);
    }
    Message::instance().setMessage(buf, MSG_HINT);
}

/**
 * 解析一对一目标：形如 "192.168.1.5" 的 IP（与订阅端注册的本地 IP 对应，
 * 见 Subscriber::setup），或十六进制的订阅端 ssid / 自定义 dstId。
 * 空字符串或无法解析时返回 0，即按 topic 广播。
 */
static uint64_t parsePublishTarget(const std::string& target)
{
    if (target.empty())
        return 0;
    if (target.find('.') != std::string::npos) {
        const unsigned int ip = inet_addr(target.c_str());
        if (ip == INADDR_NONE)
            return 0;
        return static_cast<uint64_t>(ip);
    }
    return strtoull(target.c_str(), nullptr, 16);
}

JNIEXPORT jint CPP_FUNC_CALL(StartSubscribe)(JNIEnv* env, jclass, jstring addr, jint port, jstring topic, jstring, jint)
{
    std::string address = Jstring2Cstring(env, addr);
    const std::string topicHex = Jstring2Cstring(env, topic);
    uint32_t iTopic = 0;
    if (!parseTopicHex(topicHex, iTopic)) {
        Message::instance().setMessage("Subscribe failed: topic is empty or not hex", TOAST);
        return -1;
    }

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

    if (g_subscribing.exchange(true)) {
        // 上一路尚未退出，先将本路拒绝：并非失败，仅无需再启动一路
        Message::instance().setMessage("Subscribe is already running", TOAST);
        return 0;
    }
    try {
        std::thread task(SubscribeTask, std::move(address), port, iTopic, RecvHook);
        task.detach();
    } catch (const std::exception& e) {
        g_subscribing.store(false);
        Message::instance().setMessage("Subscribe failed: cannot start thread", TOAST);
        LOGE("StartSubscribe thread error: %s", e.what());
        return -1;
    }
    return 0;
}

JNIEXPORT void CPP_FUNC_CALL(QuitSubscribe)(JNIEnv*, jclass)
{
    Scadup::Subscriber::exit();
}

JNIEXPORT void CPP_FUNC_CALL(Publish)(JNIEnv* env, jclass, jstring topic, jstring message,
                                      jstring addr, jint port, jstring target)
{
    std::string topicHex = Jstring2Cstring(env, topic);
    std::string payload = Jstring2Cstring(env, message);
    // target 为空表示按 topic 广播，非空时按 IP 或 ssid 单播（见 parsePublishTarget）
    const std::string targetStr = (target != nullptr) ? Jstring2Cstring(env, target) : "";

    uint32_t iTopic = 0;
    if (!parseTopicHex(topicHex, iTopic)) {
        Message::instance().setMessage("Publish failed: topic is empty or not hex", TOAST);
        return;
    }

    std::string pubAddr;
    int pubPort;
    {
        std::lock_guard<std::mutex> lock(g_paramMutex);
        // 发布框里填的地址优先，订阅时留下的那份只作兜底：否则先订阅过一次之后，
        // 再改发布框的地址永远不生效，包还是发到上一台上
        pubAddr = Jstring2Cstring(env, addr);
        pubPort = (pubAddr.empty() || port <= 0) ? 0 : port;
        if (pubAddr.empty() || pubPort <= 0) {
            pubAddr = g_pubSubParam.addr;
            pubPort = g_pubSubParam.port;
        }
    }
    if (pubAddr.empty() || pubPort <= 0) {
        Message::instance().setMessage("Publish failed: no address or port", TOAST);
        return;
    }

    const uint64_t dst = parsePublishTarget(targetStr);

    std::thread task(PublishTask, std::move(pubAddr), pubPort, iTopic, std::move(payload), dst);
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
    if (CpuRenderView::setupSurfaceView(env, texture) <= 0) {
        LOGE("setup surface fail while [updateEglSurface]");
        return -3;
    }
    ANativeWindow* window = EglGpuRender::OpenGLSurface();
    if (window != nullptr) {
        GLuint program = EglShader::GetShaderProgram();
        if (program == 0) {
            LOGE("GetShaderProgram failed while [updateEglSurface]");
            EglGpuRender::CloseGLSurface();
            return -2;
        }
        // FrameRender 通过 EGL2.glProgram 持有着色器程序，未赋值时 glUseProgram(0) 不会绘制任何内容
        EGL2.glProgram = program;
        EglTexture::SetTextureBuffers(program);
        if (EGL2.width == 0 || EGL2.height == 0) {
            LOGE("render size not set, call setRenderSize first");
            EglGpuRender::CloseGLSurface();
            return -4;
        }
        LOGD("OpenGL rendering initialized WxH = (%u, %u)", EGL2.width, EGL2.height);
        // 一帧 I420 是 w*h*1.5 字节，切片按这个来，回调里取 U/V 才不越过缓冲末尾
        size_t frameSize = (size_t) EGL2.width * EGL2.height * 3 / 2;
        int state = FileUtils::ReadBinaryFile(g_filename, frameSize, EglGpuRender::FrameRender);
        EglGpuRender::CloseGLSurface();
        return state;
    } else {
        LOGE("native window is null while [updateEglSurface]");
        return -1;
    }
}

JNIEXPORT jint JNICALL CPP_FUNC_VIEW(updateEglTexture)(JNIEnv* env, jclass, jobject texture)
{
    if (CpuRenderView::setupSurfaceView(env, texture) <= 0) {
        LOGE("setup surface fail while [updateEglTexture]");
        return -3;
    }
    ANativeWindow* window = EglGpuRender::OpenGLSurface();
    if (window != nullptr) {
        LOGD("OpenGL rendering initialized");
        int state = EglGpuRender::DrawRGBTexture(g_filename.c_str());
        EglGpuRender::CloseGLSurface();
        return state;
    } else {
        LOGE("native window is null while [updateEglTexture]");
        return -1;
    }
}

JNIEXPORT jint JNICALL CPP_FUNC_VIEW(updateCpuTexture)(JNIEnv* env, jclass, jobject texture, jint item)
{
    switch (item) {
    case 0:
        // 这一档没有实现
        return -1;
    case 3: {
        if (CpuRenderView::setupSurfaceView(env, texture) <= 0) {
            LOGE("setup surface fail while [updateCpuTexture]");
            return -4;
        }
        /**
         * CPU 渲染图片：BMP → RGBA → 直接写进 window buffer。
         *
         * 直接解码成 RGBA 再交给 CpuRenderView：LoadDIBitmap 给的是 3 通道 RGB，
         * 当 RGBA 拷会通道错位、行长短一截，读到后面就越界。
         */
        unsigned char* rgba = nullptr;
        BITMAPPROP prop = BitmapToRgba(g_filename.c_str(), &rgba);
        if (prop.blSize <= 0 || rgba == nullptr) {
            LOGE("decode [%s] failed, code = %ld", g_filename.c_str(), prop.blSize);
            free(rgba);
            Message::instance().setMessage("Not a 24/32-bit BMP file", TOAST);
            CpuRenderView::releaseSurfaceView(env);
            return -2;
        }
        LOGD("CPU rendering initialized [%u]x[%u]", prop.biWidth, prop.biHeight);
        CpuRenderView::setDisplaySize((int)prop.biHeight, (int)prop.biWidth);
        CpuRenderView::drawRgba(rgba, (int)prop.biWidth, (int)prop.biHeight, (size_t)prop.blSize);
        free(rgba);
        CpuRenderView::releaseSurfaceView(env);
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
    if (CpuRenderView::setupSurfaceView(env, texture) <= 0) {
        LOGE("native window is null while [updateCpuVideoFile]");
        return -1;
    }
    if (g_width <= 0 || g_height <= 0) {
        LOGE("render size not set (%dx%d), call setRenderSize first", g_width, g_height);
        CpuRenderView::releaseSurfaceView(env);
        return -2;
    }
    LOGD("CPU surface rendering initialized(%d, %d)", g_height, g_width);
    CpuRenderView::setDisplaySize(g_height, g_width);
    // 窗口是 RGBA8888，切片按 w*h*4 字节来，一帧才铺满整屏
    int state = FileUtils::ReadBinaryFile(g_filename, (size_t) g_width * g_height * 4,
                                          CpuRenderView::drawSurface);
    CpuRenderView::releaseSurfaceView(env);
    return state;
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
// 接收落盘路径缓存：applySavePath() 通常先于 start/connect 调用，
// 那时 g_fileMsg 还没创建，所以先把路径存在这里，建好实例再套用（否则会落到 "./"）。
static std::string g_fileSavePath;

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
        Message::instance().setMessage(message, MSG_STAT);
    }
    delete sock;
    return 0;
}

void callback(char* data)
{
    if (data[0] != '\0') {
        // 收到的数据仍走 UDP_SERVER：Java 侧按「第一条是启动状态、之后是收到的数据」分开显示
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
            std::string message = "udp receiver starts · port " + std::to_string(port);
            Message::instance().setMessage(message, UDP_SERVER);
            do {
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
    /** 这里不打日志：每包一次太密，而且 LOGD 不输出时参数照旧求值，格式化照样白跑一遍。 */
    (void)data;
    (void)size;
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

// ============ KCP 服务端 / 客户端控制状态 ============
static std::mutex   g_kcpMutex;
static KcpSocket* g_kcpServer = nullptr;
static std::thread* g_kcpServerThread = nullptr;
static KcpSocket* g_kcpClient = nullptr;
static std::thread* g_kcpClientThread = nullptr;

/** 移除尾部的 '\0' 与换行，以免拼接至 UI 时出现空行 */
static std::string trimTail(const char* data, int len)
{
    std::string body(data, len);
    while (!body.empty() && (body.back() == '\0' || body.back() == '\n')) {
        body.pop_back();
    }
    return body;
}

/**
 * KCP 服务端收到一条消息：剥掉 8 字节头，把真正的载荷连同来源一起推给页面（KCP_HINT）。
 * 服务端同时接着局域网里多个对端，不带上来路就分不清该报文是谁发的。
 */
static void KcpServerRecv(const char* data, int len, const char* peerIp, int peerPort, void*)
{
    unsigned int sn = 0;
    const char* payload = data;
    int payloadLen = len;
    if (len >= KCP_ECHO_HDR) {
        memcpy(&sn, data, sizeof(sn));
        payload = data + KCP_ECHO_HDR;
        payloadLen = len - KCP_ECHO_HDR;
    }
    std::string body = trimTail(payload, payloadLen);
    std::string from = (peerIp != nullptr ? peerIp : "?");
    from += ":" + std::to_string(peerPort);
    Message::instance().setMessage(
        "KCP recv ← " + from + " sn=" + std::to_string(sn) + " " + std::to_string((int)body.size())
        + "B: " + body, KCP_HINT);
}

/**
 * KCP 客户端收到服务端回射的报文：按包头里的时间戳算 RTT，推给页面 status 区（KCP_CLIENT）。
 */
static void KcpClientEcho(unsigned int sn, unsigned int rttMs, const char* data, int len, void*)
{
    std::string body = trimTail(data, len);
    Message::instance().setMessage(
        "KCP echo sn=" + std::to_string(sn) + " rtt=" + std::to_string(rttMs) + "ms "
        + std::to_string((int)body.size()) + "B: " + body, KCP_CLIENT);
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startKcpServer)(JNIEnv*, jclass, jint port)
{
    std::lock_guard<std::mutex> lock(g_kcpMutex);
    if (g_kcpServer != nullptr) {
        Message::instance().setMessage("KCP server already running", KCP_HINT);
        return 1;
    }
    auto* sock = new KcpSocket();
    int ret = sock->init(port, false);
    if (ret != 0) {
        std::string err = "KCP server start failed(" + std::to_string(ret) + "): " + strerror(errno);
        Message::instance().setMessage(err, KCP_HINT);
        delete sock;
        return ret;
    }
    sock->setRecvCallback(KcpServerRecv, nullptr);
    g_kcpServer = sock;
    // 循环线程只负责跑 KCP 状态机，退出后再由 stopKcpServer() 统一回收对象
    g_kcpServerThread = new std::thread([sock]() -> void {
        sock->startServer();
        });
    Message::instance().setMessage("KCP server started · UDP " + std::to_string(port), KCP_HINT);
    LOGI("KCP server started on port %d\n", port);
    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(stopKcpServer)(JNIEnv*, jclass)
{
    KcpSocket* sock = nullptr;
    std::thread* th = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_kcpMutex);
        sock = g_kcpServer;
        th = g_kcpServerThread;
        g_kcpServer = nullptr;
        g_kcpServerThread = nullptr;
    }
    if (sock == nullptr) {
        Message::instance().setMessage("KCP server not running", KCP_HINT);
        return -1;
    }
    sock->stop();                       // 循环里 recvfrom 是 MSG_DONTWAIT，1ms 一跳即可退出
    if (th != nullptr) {
        if (th->joinable()) {
            th->join();
        }
        delete th;
    }
    sock->destroy();
    delete sock;
    Message::instance().setMessage("KCP server cleanup ok", KCP_HINT);
    return 0;
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startKcpClient)(JNIEnv* env, jclass, jstring ipstr, jint port)
{
    std::string addr = Jstring2Cstring(env, ipstr);
    std::lock_guard<std::mutex> lock(g_kcpMutex);
    if (g_kcpClient != nullptr) {
        Message::instance().setMessage("KCP client already started", KCP_CLIENT);
        return 1;
    }
    auto* sock = new KcpSocket();
    int ret = sock->init(port, true, addr.c_str());
    if (ret != 0) {
        std::string err = "KCP client start failed(" + std::to_string(ret) + ")";
        Message::instance().setMessage(err, KCP_CLIENT);
        delete sock;
        return ret;
    }
    // 客户端只关心自己发出去的包什么时候被回射回来，所以挂的是 echo 回调
    sock->setEchoCallback(KcpClientEcho, nullptr);
    g_kcpClient = sock;
    g_kcpClientThread = new std::thread([sock]() -> void {
        sock->startClient();
        });
    Message::instance().setMessage(
        "KCP client started → " + addr + ":" + std::to_string(port), KCP_CLIENT);
    return 0;
}       

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(stopKcpClient)(JNIEnv*, jclass)
{
    KcpSocket* sock = nullptr;
    std::thread* th = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_kcpMutex);
        sock = g_kcpClient;
        th = g_kcpClientThread;
        g_kcpClient = nullptr;
        g_kcpClientThread = nullptr;
    }
    if (sock == nullptr) {
        Message::instance().setMessage("KCP client not started yet", KCP_CLIENT);
        return -1;
    }
    sock->stop();
    if (th != nullptr) {
        if (th->joinable()) {
            th->join();
        }
        delete th;
    }
    sock->destroy();
    delete sock;
    Message::instance().setMessage("KCP client cleanup ok", KCP_CLIENT);
    return 0;
}

/**
 * 客户端发一条数据（页面传进来的是十六进制随机数）：带上 sn + 时间戳的 8 字节头，
 * 服务端会原样回射，客户端收到后算 RTT。返回 ikcp_send 的结果，<0 为失败。
 */
JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(sendKcpData)(JNIEnv* env, jclass, jstring text, jint)
{
    std::string data = Jstring2Cstring(env, text);
    KcpSocket* sock = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_kcpMutex);
        sock = g_kcpClient;
    }
    if (sock == nullptr) {
        Message::instance().setMessage("KCP client not started yet", KCP_CLIENT);
        return -1;
    }
    int size = (int)data.size();
    int ret = sock->sendEcho(data.c_str(), size);
    if (ret < 0) {
        Message::instance().setMessage("KCP send failed(" + std::to_string(ret) + ")", KCP_CLIENT);
    } else {
        Message::instance().setMessage(
            "KCP sent sn=" + std::to_string(sock->lastSn()) + " " + std::to_string(size) + "B: "
            + data.substr(0, size), KCP_CLIENT);
    }
    LOGI("sendKcpData %d bytes ret=%d sn=%u\n", size, ret, sock->lastSn());
    return ret;
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
    // 套用缓存的接收落盘路径（Java 端 applySavePath 通常先于本调用，那时 g_fileMsg 还不存在）
    if (!g_fileSavePath.empty()) {
        g_fileMsg->setSavePath(g_fileSavePath);
        LOGI("FileMsg server saving to: %s", g_fileSavePath.c_str());
    }
    int ret = g_fileMsg->startServer((unsigned short)port);
    if (ret < 0) {
        delete g_fileMsg;
        g_fileMsg = nullptr;
        return ret;
    }
    Message::instance().setMessage("FileMsg Server Started on Port " + std::to_string(port), MSG_STAT);
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
    if (!g_fileSavePath.empty()) {
        g_fileMsg->setSavePath(g_fileSavePath);
    }
    int ret = g_fileMsg->connectToServer(address, (unsigned short)port);
    if (ret < 0) {
        delete g_fileMsg;
        g_fileMsg = nullptr;
        return ret;
    }
    Message::instance().setMessage("Connected to FileMsg server " + address + ":" + std::to_string(port), MSG_STAT);
    return 0;
}

JNIEXPORT void JNICALL CPP_FUNC_NETWORK(disconnectFileMsg)(JNIEnv*, jclass)
{
    std::lock_guard<std::mutex> lock(g_fileTransMutex);
    if (g_fileMsg != nullptr) {
        g_fileMsg->disconnect();
        delete g_fileMsg;
        g_fileMsg = nullptr;
        Message::instance().setMessage("FileMsg disconnected", MSG_STAT);
    }
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(postLocalFile)(JNIEnv* env, jclass, jstring filePath)
{
    std::string path = Jstring2Cstring(env, filePath);
    std::lock_guard<std::mutex> lock(g_fileTransMutex);
    if (g_fileMsg == nullptr || !g_fileMsg->isConnected()) {
        LOGE("FileMsg not connected");
        return -1;
    }
    int ret = g_fileMsg->postLocalFile(path);
    if (ret < 0) {
        Message::instance().setMessage("File send failed", MSG_STAT);
        return ret;
    }
    Message::instance().setMessage("File send complete: " + path, MSG_STAT);
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
    g_fileSavePath = savePath;
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
        Message::instance().setMessage("FileMsg Server Exit", MSG_STAT);
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

// ============ 局域网文本消息（发现 + 收发，全部在 C++ 侧） ============

static std::mutex g_lanMutex;

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(startLanMessenger)(JNIEnv* env, jclass,
                                                            jstring name, jstring selfIp, jint port)
{
    std::string device = Jstring2Cstring(env, name);
    std::string address = Jstring2Cstring(env, selfIp);
    std::lock_guard<std::mutex> lock(g_lanMutex);
    int ret = LanMessenger::instance().start(device, address, port);
    if (ret < 0) {
        Message::instance().setMessage(
            "LAN: start failed(" + std::to_string(ret) + ")", LAN_MESSAGE);
    }
    return ret;
}

JNIEXPORT void JNICALL CPP_FUNC_NETWORK(stopLanMessenger)(JNIEnv*, jclass)
{
    std::lock_guard<std::mutex> lock(g_lanMutex);
    LanMessenger::instance().stop();
}

JNIEXPORT void JNICALL CPP_FUNC_NETWORK(setLanDiscoveryPort)(JNIEnv*, jclass, jint port)
{
    std::lock_guard<std::mutex> lock(g_lanMutex);
    // 发现与收发各占一个端口：收发是 39001 的 KCP 会话，发现是 39000 的广播，
    // 两者互不相干，改发现端口不会动到收发
    LanMessenger::instance().setDiscoveryPort(port);
}

JNIEXPORT void JNICALL CPP_FUNC_NETWORK(setLanDiscoveryAddress)(JNIEnv* env, jclass, jstring address)
{
    std::lock_guard<std::mutex> lock(g_lanMutex);
    std::string addr = Jstring2Cstring(env, address);
    LanMessenger::instance().setDiscoveryAddress(addr);
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(sendLanMessage)(JNIEnv* env, jclass,
                                                         jstring ip, jint port, jstring text)
{
    std::string address = Jstring2Cstring(env, ip);
    std::string body = Jstring2Cstring(env, text);
    if (address.empty() || port <= 0 || body.empty()) {
        return -1;
    }
    if (!LanMessenger::instance().isRunning()) {
        return -2;
    }
    // sendText() 只入队 + 发一条 SENDING 事件，publish 由 LanMessenger 自己的发送线程做，
    // 所以这里不会卡住调用方
    return LanMessenger::instance().sendText(address, port, body);
}

JNIEXPORT jint JNICALL CPP_FUNC_NETWORK(sendLanImage)(JNIEnv* env, jclass,
                                                       jstring ip, jint port, jstring base64)
{
    std::string address = Jstring2Cstring(env, ip);
    std::string body = Jstring2Cstring(env, base64);
    if (address.empty() || port <= 0 || body.empty()) {
        return -1;
    }
    if (!LanMessenger::instance().isRunning()) {
        return -2;
    }
    // 超过 LAN_MAX_IMAGE_CHARS 会返回 -4
    return LanMessenger::instance().sendImage(address, port, body);
}

JNIEXPORT void JNICALL CPP_FUNC_NETWORK(refreshLanPeers)(JNIEnv*, jclass)
{
    std::lock_guard<std::mutex> lock(g_lanMutex);
    LanMessenger::instance().refresh();
}
