#include <sys/types.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cstring>
#include <string>
#include "KcpSocket.h"
#ifndef LOG_TAG
#define LOG_TAG "KcpSocket"
#endif
#include <cerrno>
#include <utils/logging.h>

#define KCP_CONV_VAL 0x123

/** KCP 的毫秒时钟：ikcp_update 的时间基准，也是算 RTT 用的同一个时间 */
static inline unsigned int kcpClockMs()
{
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return (unsigned int)((long long)tv.tv_sec * 1000 + tv.tv_usec / 1000);
}

static inline void kcpSleepMs(unsigned long ms)
{
    usleep(ms * 1000);
}

int udpOutput(const char* buff, int len, ikcpcb* kcp, void* user)
{
    if (user == nullptr)
        return -1;
    auto* msg = (stKcpMsg*)user;
    if (msg->sockfd <= 0)
        return -1;
    struct sockaddr_in* dst = msg->isClient ? &msg->addr : &msg->clientAddr;
    ssize_t n = ::sendto(msg->sockfd, buff, len, 0, (struct sockaddr*)dst, sizeof(struct sockaddr_in));
    if (n >= 0) {
        if (!msg->isClient)
            LOGI("udpOutPut-sendto: %zd bytes\n", n);
        return (int)n;
    } else {
        LOGI("udpOutput %s: %zd bytes sendto fail: %s\n", (msg->isClient ? "client" : "server"), n, strerror(errno));
        return -1;
    }
}

/** KCP 的三档参数：default / normal / fast */
enum class KcpMode {
    Default,   // ikcp_nodelay(kcp, 0, 10, 0, 0)
    Normal,    // 关掉流控
    Fast       // nodelay + 快速重传 + 关流控
};

static void setupKcp(stKcpMsg* msg, KcpMode mode = KcpMode::Default)
{
    ikcpcb* kcp = ikcp_create(KCP_CONV_VAL, (void*)msg);
    kcp->output = udpOutput;
    if (mode == KcpMode::Normal) {
        ikcp_nodelay(kcp, 0, 10, 0, 1);
    } else if (mode == KcpMode::Fast) {
        // 第二个参数 nodelay 打开若干常规加速；resend=2 表示跳过两次 ACK 就重传；最后一位置 1 关掉流控
        ikcp_nodelay(kcp, 2, 10, 2, 1);
        kcp->rx_minrto = 10;
        kcp->fastresend = 1;
    } else {
        ikcp_nodelay(kcp, 0, 10, 0, 0);
    }
    // 收发窗口 128：给丢包重发留足余量
    ikcp_wndsize(kcp, 128, 128);
    msg->pkcp = kcp;
}

KcpSocket::KcpSocket()
    : m_running(false), m_recvCb(nullptr), m_recvUser(nullptr),
      m_echoCb(nullptr), m_echoUser(nullptr), m_sn(0)
{
    memset(&m_kcpMsg, 0, sizeof(m_kcpMsg));
    memset(m_hostIp, 0, sizeof(m_hostIp));
}

KcpSocket::~KcpSocket()
{
    stop();
    destroy();
}

int KcpSocket::init(int port, bool client, const char* ip)
{
    m_kcpMsg.port = port;
    m_kcpMsg.isClient = client;
    m_kcpMsg.pkcp = nullptr;
    m_kcpMsg.sockfd = 0;
    m_kcpMsg.ipstr = nullptr;
    if (ip != nullptr) {
        snprintf(m_hostIp, sizeof(m_hostIp), "%s", ip);
        m_kcpMsg.ipstr = (unsigned char*)m_hostIp;
    }

    if (!m_kcpMsg.isClient) {
        setupKcp(&m_kcpMsg);
    }

    m_kcpMsg.sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (m_kcpMsg.sockfd < 0) {
        LOGE("socket fail: %s\n", strerror(errno));
        return -2;
    }

    bzero(&m_kcpMsg.addr, sizeof(m_kcpMsg.addr));
    bzero(&m_kcpMsg.clientAddr, sizeof(m_kcpMsg.clientAddr));
    m_kcpMsg.addr.sin_family = AF_INET;
    m_kcpMsg.addr.sin_port = htons(m_kcpMsg.port);
    if (!m_kcpMsg.isClient) {
        m_kcpMsg.addr.sin_addr.s_addr = htonl(INADDR_ANY);
        if (bind(m_kcpMsg.sockfd, (struct sockaddr*)&(m_kcpMsg.addr), sizeof(struct sockaddr_in)) < 0) {
            LOGE("bind %d fail: %s\n", port, strerror(errno));
            ::close(m_kcpMsg.sockfd);
            m_kcpMsg.sockfd = 0;
            return -3;
        }
    } else {
        m_kcpMsg.addr.sin_addr.s_addr = inet_addr((char*)m_kcpMsg.ipstr);
    }

    if (m_kcpMsg.isClient) {
        setupKcp(&m_kcpMsg);
        LOGI("KCP client init(%d) to %s:%d success\n", m_kcpMsg.sockfd, m_kcpMsg.ipstr, m_kcpMsg.port);
    } else {
        LOGI("KCP server init(%d) port: %d success\n", m_kcpMsg.sockfd, m_kcpMsg.port);
    }

    m_running = true;
    return 0;
}

void KcpSocket::setRecvCallback(KcpRecvCallback cb, void* user)
{
    m_recvCb = cb;
    m_recvUser = user;
}

void KcpSocket::setEchoCallback(KcpEchoCallback cb, void* user)
{
    m_echoCb = cb;
    m_echoUser = user;
}

void KcpSocket::notifyRecv(const char* data, int len)
{
    if (m_recvCb != nullptr && data != nullptr && len > 0) {
        m_recvCb(data, len, m_recvUser);
    }
}

int KcpSocket::send(const char* data, int len)
{
    if (!m_running || data == nullptr || len <= 0) {
        return -1;
    }
    std::lock_guard<std::mutex> lock(m_lock);
    if (m_kcpMsg.pkcp == nullptr) {
        return -2;
    }
    return ikcp_send(m_kcpMsg.pkcp, data, len);
}

int KcpSocket::sendEcho(const char* payload, int len)
{
    if (!m_running || payload == nullptr || len <= 0) {
        return -1;
    }
    char buf[KCP_MSG_LEN + KCP_ECHO_HDR];
    const int bufCap = (int)sizeof(buf);
    if (len + KCP_ECHO_HDR > bufCap) {
        return -3;
    }

    std::lock_guard<std::mutex> lock(m_lock);
    if (m_kcpMsg.pkcp == nullptr) {
        return -2;
    }
    // 头部 8 字节：前 4 字节序号，后 4 字节发送时刻
    unsigned int sn = ++m_sn;
    unsigned int ts = kcpClockMs();
    memcpy(buf, &sn, sizeof(sn));
    memcpy(buf + 4, &ts, sizeof(ts));
    memcpy(buf + KCP_ECHO_HDR, payload, len);
    return ikcp_send(m_kcpMsg.pkcp, buf, len + KCP_ECHO_HDR);
}

unsigned int KcpSocket::lastSn() const
{
    return m_sn;
}

void KcpSocket::startClient()
{
    if (!m_running) {
        LOGE("you should init at first\n");
        return;
    }
    runLoop();
}

void KcpSocket::startServer()
{
    if (!m_running) {
        LOGE("you should init at first\n");
        return;
    }
    runLoop();
}

/**
 * 客户端和服务端跑的是同一个循环，区别只在 handleMessage() 里：
 *  - 服务端：上报后原样回射
 *  - 客户端：拿回射包算 RTT
 */
void KcpSocket::runLoop()
{
    char buffer[KCP_MSG_LEN + 24];
    const int bufferCap = (int)sizeof(buffer);
    int rcvd = 0;
    while (m_running) {
        kcpSleepMs(1);
        {
            // ikcp_update 内部会 ikcp_flush，发送队列里的包袱是这时候真正发出去的
            std::lock_guard<std::mutex> lock(m_lock);
            ikcp_update(m_kcpMsg.pkcp, kcpClockMs());
        }

        // 1) 收裸 UDP 报文：不管是不是 KCP 控制报文，全部交给 ikcp_input
        while (m_running) {
            struct sockaddr_in fromAddr = {};
            socklen_t addrLen = sizeof(fromAddr);
            ssize_t n = ::recvfrom(m_kcpMsg.sockfd, buffer, bufferCap, MSG_DONTWAIT,
                                   (struct sockaddr*)&fromAddr, &addrLen);
            if (n <= 0) {
                break;
            }
            if (!m_kcpMsg.isClient) {
                // 服务端记住对端地址：回射时 udpOutput 要往这儿发
                m_kcpMsg.clientAddr = fromAddr;
            }
            int state;
            {
                std::lock_guard<std::mutex> lock(m_lock);
                state = ikcp_input(m_kcpMsg.pkcp, buffer, n);
            }
            if (state < 0) {
                LOGE("ikcp_input fail, state = %d\n", state);
            }
        }

        // 2) 取应用层消息：ikcp_recv 解出来的就是对端 ikcp_send 的那一整条
        while (m_running) {
            int size;
            {
                std::lock_guard<std::mutex> lock(m_lock);
                size = ikcp_recv(m_kcpMsg.pkcp, buffer, bufferCap);
            }
            if (size <= 0) {
                break;
            }
            rcvd++;
            struct sockaddr_in& peer = m_kcpMsg.isClient ? m_kcpMsg.addr : m_kcpMsg.clientAddr;
            LOGI("%s recv %dB (total=%d) from %s:%d\n", m_kcpMsg.isClient ? "client" : "server",
                 size, rcvd, inet_ntoa(peer.sin_addr), ntohs(peer.sin_port));
            handleMessage(buffer, size);
        }
    }
}

void KcpSocket::handleMessage(const char* data, int size)
{
    if (data == nullptr || size <= 0) {
        return;
    }
    notifyRecv(data, size);

    if (!m_kcpMsg.isClient) {
        // 服务端收到什么原样射回去，客户端才好算 RTT
        std::lock_guard<std::mutex> lock(m_lock);
        ikcp_send(m_kcpMsg.pkcp, data, size);
        return;
    }

    // 客户端：回射包前 8 字节是 sn + 发送时刻，跟当前时间一减就是 RTT
    if (size < KCP_ECHO_HDR || m_echoCb == nullptr) {
        return;
    }
    unsigned int sn = 0;
    unsigned int ts = 0;
    memcpy(&sn, data, sizeof(sn));
    memcpy(&ts, data + 4, sizeof(ts));
    unsigned int rtt = kcpClockMs() - ts;
    m_echoCb(sn, rtt, data + KCP_ECHO_HDR, size - KCP_ECHO_HDR, m_echoUser);
}

void KcpSocket::stop()
{
    m_running = false;
}

void KcpSocket::destroy()
{
    if (m_kcpMsg.sockfd != 0) {
        close(m_kcpMsg.sockfd);
        m_kcpMsg.sockfd = 0;
    }
    if (m_kcpMsg.pkcp != nullptr) {
        ikcp_release(m_kcpMsg.pkcp);
        m_kcpMsg.pkcp = nullptr;
    }
}
