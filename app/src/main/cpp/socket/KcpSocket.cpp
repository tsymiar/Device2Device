#include <sys/types.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cstring>
#include <string>
#include <vector>
#include "KcpSocket.h"
#ifndef LOG_TAG
#define LOG_TAG "KcpSocket"
#endif
#include <cerrno>
#include <utils/logging.h>


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

/**
 * ikcp 的出口：把 KCP 要发的报文交给 UDP 发出。
 *
 * user 挂的是 KcpPeer，里面带着 socket 与目标地址 —— 服务端对每个对端各有一份，
 * 所以回射不会串台。
 */
int udpOutput(const char* buff, int len, ikcpcb* kcp, void* user)
{
    (void)kcp;
    if (user == nullptr || buff == nullptr || len <= 0) {
        return -1;
    }
    auto* peer = (KcpPeer*)user;
    if (peer->sockfd <= 0) {
        return -1;
    }
    ssize_t n = ::sendto(peer->sockfd, buff, (size_t)len, 0,
                         (struct sockaddr*)&peer->addr, sizeof(struct sockaddr_in));
    if (n >= 0) {
        return (int)n;
    }
    LOGI("udpOutput sendto fail: %zd (%s)\n", n, strerror(errno));
    return -1;
}

/** 建一份 KCP 会话：output 的 user 就是这个 peer，sendto 才知道发往哪里 */
static void setupKcp(KcpPeer* peer, IUINT32 conv)
{
    ikcpcb* kcp = ikcp_create(conv, (void*)peer);
    kcp->output = udpOutput;
    ikcp_nodelay(kcp, 0, 10, 0, 0);
    // 收发窗口 128：给丢包重发留足余量
    ikcp_wndsize(kcp, 128, 128);
    peer->kcp = kcp;
    peer->conv = conv;
}

/**
 * 会话的 map key：地址 + conv。
 *
 * 带上 conv 是因为对端重启后新进程的 conv 与序号都从零开始，地址端口却没变。
 * 只按地址建会话时，新旧两拨包会挤进同一份会话，序号对不上：要么被当成旧数据悄悄丢掉，
 * 要么为了"认新的"把会话反复重建，结果谁都建不稳。按 (地址, conv) 分开，
 * 旧会话自然被超时回收，新会话从第一个报文起就是连续的。
 */
static std::string peerKey(const struct sockaddr_in& addr, IUINT32 conv)
{
    char ip[INET_ADDRSTRLEN] = {0};
    inet_ntop(AF_INET, &addr.sin_addr, ip, sizeof(ip));
    char key[64];
    snprintf(key, sizeof(key), "%s:%u:%08x", ip, (unsigned)ntohs(addr.sin_port), conv);
    return std::string(key);
}

static std::string peerAddrText(const struct sockaddr_in& addr)
{
    char ip[INET_ADDRSTRLEN] = {0};
    inet_ntop(AF_INET, &addr.sin_addr, ip, sizeof(ip));
    return std::string(ip) + ":" + std::to_string(ntohs(addr.sin_port));
}

KcpSocket::KcpSocket()
    : m_running(false), m_recvCb(nullptr), m_recvUser(nullptr),
      m_echoCb(nullptr), m_echoUser(nullptr), m_echoBack(true), m_sn(0)
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

    // 本实例这一轮的 conv：每次 init() 换一个。
    // 所有会话共用一个写死的 conv 时，对端重启后新进程的序号从零开始，而接收方还留着
    // 指向同一地址的旧会话（rcv_nxt 已经走远），新会话的头几个包会因序号落在窗口之外
    // 被当成旧数据悄悄丢掉，只能等应用层隔几秒重发才补上。conv 跟着实例走，
    // 接收方就能凭它认出「同一个地址、但是另一个人」并重建会话。
    m_conv = (IUINT32)(kcpClockMs() ^ ((IUINT32)::getpid() << 16) ^ (IUINT32)::rand());
    if (m_conv == 0) {
        m_conv = 1;
    }

    m_kcpMsg.sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (m_kcpMsg.sockfd < 0) {
        LOGE("socket fail: %s\n", strerror(errno));
        return -2;
    }

    // 服务端换端口重启时上一次的 TIME_WAIT / 残留绑定不该让这次起不来
    int reuse = 1;
    setsockopt(m_kcpMsg.sockfd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    bzero(&m_kcpMsg.addr, sizeof(m_kcpMsg.addr));
    bzero(&m_kcpMsg.clientAddr, sizeof(m_kcpMsg.clientAddr));
    m_kcpMsg.addr.sin_family = AF_INET;
    m_kcpMsg.addr.sin_port = htons(m_kcpMsg.port);
    if (!m_kcpMsg.isClient) {
        // 局域网支持的关键：绑 0.0.0.0 而不是 127.0.0.1，
        // 这样同网段其它设备往本机 IP 发也能进来
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
        auto* peer = new KcpPeer();
        peer->sockfd = m_kcpMsg.sockfd;
        peer->addr = m_kcpMsg.addr;
        peer->lastActiveMs = kcpClockMs();
        setupKcp(peer, m_conv);
        m_self = peer;
        m_kcpMsg.pkcp = peer->kcp;
        LOGI("KCP client init(%d) to %s:%d success\n", m_kcpMsg.sockfd, m_kcpMsg.ipstr, m_kcpMsg.port);
    } else {
        LOGI("KCP server init(%d) port: %d (0.0.0.0, LAN reachable)\n", m_kcpMsg.sockfd, m_kcpMsg.port);
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

void KcpSocket::setEchoEnabled(bool on)
{
    m_echoBack = on;
}

void KcpSocket::notifyRecv(const char* data, int len, const char* peerIp, int peerPort)
{
    if (m_recvCb != nullptr && data != nullptr && len > 0) {
        m_recvCb(data, len, peerIp, peerPort, m_recvUser);
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

int KcpSocket::sendTo(const char* ip, int port, const char* data, int len)
{
    if (!m_running || data == nullptr || len <= 0 || ip == nullptr || port <= 0) {
        return -1;
    }
    std::lock_guard<std::mutex> lock(m_lock);
    if (m_kcpMsg.isClient) {
        // 客户端只有一份会话，地址参数用不上
        if (m_self == nullptr || m_self->kcp == nullptr) {
            return -2;
        }
        m_self->lastActiveMs = kcpClockMs();
        return ikcp_send(m_self->kcp, data, len);
    }
    KcpPeer* peer = findOrCreatePeerByAddrLocked(ip, port);
    if (peer == nullptr || peer->kcp == nullptr) {
        return -2;
    }
    // 主动发话也算活着：会话不会被 prune 掉，对端的 rcv_nxt 才不会跟我们对不上
    peer->lastActiveMs = kcpClockMs();
    int ret = ikcp_send(peer->kcp, data, len);
    if (ret < 0) {
        LOGE("sendTo %s:%d ikcp_send fail: %d\n", ip, port, ret);
    }
    return ret;
}

unsigned int KcpSocket::lastSn() const
{
    return m_sn;
}

int KcpSocket::peerCount() const
{
    std::lock_guard<std::mutex> lock(m_lock);
    return (int)m_peers.size();
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

KcpPeer* KcpSocket::findOrCreatePeerLocked(const struct sockaddr_in& from, IUINT32 conv)
{
    std::string key = peerKey(from, conv);
    auto it = m_peers.find(key);
    if (it != m_peers.end()) {
        it->second->lastActiveMs = kcpClockMs();
        return it->second;
    }
    auto* peer = new KcpPeer();
    peer->sockfd = m_kcpMsg.sockfd;
    peer->addr = from;
    peer->lastActiveMs = kcpClockMs();
    setupKcp(peer, conv);
    m_peers[key] = peer;
    LOGI("KCP server: new peer %s conv=%08x (total=%zu)\n",
         peerAddrText(from).c_str(), conv, m_peers.size());
    return peer;
}

KcpPeer* KcpSocket::findOrCreatePeerByAddrLocked(const char* ip, int port)
{
    struct sockaddr_in addr;
    bzero(&addr, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short)port);
    if (inet_pton(AF_INET, ip, &addr.sin_addr) != 1) {
        LOGE("sendTo: bad address %s\n", ip == nullptr ? "(null)" : ip);
        return nullptr;
    }
    // 这个地址上可能已经有一份收包时建的会话（它的 conv 是对端的），优先复用：
    // 主动发话与回话共用一份，一个对端才不会在表里占两条。局域网上对端没几个，
    // 线性扫一遍比另维护一张 地址→conv 的表划算。
    for (auto& kv : m_peers) {
        KcpPeer* have = kv.second;
        if (have->addr.sin_addr.s_addr == addr.sin_addr.s_addr
            && have->addr.sin_port == addr.sin_port) {
            have->lastActiveMs = kcpClockMs();
            return have;
        }
    }
    return findOrCreatePeerLocked(addr, m_conv);
}

void KcpSocket::prunePeersLocked(unsigned long nowMs)
{
    for (auto it = m_peers.begin(); it != m_peers.end();) {
        KcpPeer* peer = it->second;
        // lastActiveMs 由另一条线程写，正常不会比 nowMs 新；时钟回跳或 32 位截断绕回时
        // 就会，那时不能按无符号减法判，否则一绕回就变成"超时"
        if (nowMs >= peer->lastActiveMs
            && nowMs - peer->lastActiveMs > KCP_PEER_TIMEOUT_MS) {
            LOGI("KCP server: peer %s timeout, dropped\n", peerAddrText(peer->addr).c_str());
            ikcp_release(peer->kcp);
            delete peer;
            it = m_peers.erase(it);
        } else {
            ++it;
        }
    }
}

void KcpSocket::releasePeersLocked()
{
    for (auto& kv : m_peers) {
        if (kv.second->kcp != nullptr) {
            ikcp_release(kv.second->kcp);
        }
        delete kv.second;
    }
    m_peers.clear();
    if (m_self != nullptr) {
        if (m_self->kcp != nullptr) {
            ikcp_release(m_self->kcp);
        }
        delete m_self;
        m_self = nullptr;
        m_kcpMsg.pkcp = nullptr;
    }
}

/**
 * 客户端和服务端跑的是同一个循环，区别只在 handleMessage() 里：
 *  - 服务端：每个对端一份会话，上报后原样回射给同一个对端
 *  - 客户端：拿回射包算 RTT
 */
void KcpSocket::runLoop()
{
    // 裸 UDP 层按 ikcp 的 mtu 收：KCP_MSG_LEN 比 mtu 小，用它当缓冲会把包截断
    char buffer[KCP_UDP_BUF];
    const int bufferCap = (int)sizeof(buffer);
    int rcvd = 0;
    while (m_running) {
        kcpSleepMs(1);
        unsigned long nowMs = kcpClockMs();

        // 0) 推所有会话的状态机：ikcp_update 内部会 flush，队列里的包这时才真发出去
        {
            std::lock_guard<std::mutex> lock(m_lock);
            if (m_kcpMsg.isClient) {
                if (m_self != nullptr && m_self->kcp != nullptr) {
                    ikcp_update(m_self->kcp, nowMs);
                }
            } else {
                for (auto& kv : m_peers) {
                    ikcp_update(kv.second->kcp, nowMs);
                }
            }
        }

        // 1) 收裸 UDP 报文：不管是不是 KCP 控制报文，全部交给对应会话的 ikcp_input
        while (m_running) {
            struct sockaddr_in fromAddr = {};
            socklen_t addrLen = sizeof(fromAddr);
            ssize_t n = ::recvfrom(m_kcpMsg.sockfd, buffer, bufferCap, MSG_DONTWAIT,
                                   (struct sockaddr*)&fromAddr, &addrLen);
            if (n <= 0) {
                break;
            }
            KcpPeer* peer = nullptr;
            {
                std::lock_guard<std::mutex> lock(m_lock);
                if (m_kcpMsg.isClient) {
                    peer = m_self;
                } else {
                    // conv 从报文头取：对端重启后它是新的，findOrCreatePeerLocked 据此重建会话
                    peer = findOrCreatePeerLocked(fromAddr, ikcp_getconv(buffer));
                    m_kcpMsg.clientAddr = fromAddr;
                }
            }
            if (peer == nullptr || peer->kcp == nullptr) {
                break;
            }
            int state;
            {
                std::lock_guard<std::mutex> lock(m_lock);
                state = ikcp_input(peer->kcp, buffer, (int)n);
            }
            if (state < 0) {
                LOGE("ikcp_input fail, state = %d\n", state);
            }
        }

        // 2) 取应用层消息：ikcp_recv 解出来的就是对端 ikcp_send 的整条消息
        std::vector<KcpPeer*> snapshot;
        {
            std::lock_guard<std::mutex> lock(m_lock);
            if (m_kcpMsg.isClient) {
                if (m_self != nullptr) snapshot.push_back(m_self);
            } else {
                for (auto& kv : m_peers) snapshot.push_back(kv.second);
            }
        }
        for (KcpPeer* peer : snapshot) {
            while (m_running) {
                // 一条消息有多大由 ikcp_peeksize() 说了算：固定缓冲收时，超过 1KB 的
                // 消息 ikcp_recv 只会返回 -2，而且一直赖在接收队列里出不来
                std::vector<char> msg;
                int size = 0;
                {
                    std::lock_guard<std::mutex> lock(m_lock);
                    int peek = ikcp_peeksize(peer->kcp);
                    if (peek <= 0) {
                        break;
                    }
                    msg.resize((size_t)peek);
                    size = ikcp_recv(peer->kcp, &msg[0], peek);
                }
                if (size <= 0) {
                    break;
                }
                rcvd++;
                LOGI("%s recv %dB (total=%d) from %s\n", m_kcpMsg.isClient ? "client" : "server",
                     size, rcvd, peerAddrText(peer->addr).c_str());
                handleMessage(peer, &msg[0], size);
            }
        }

        // 3) 服务端回收长时间无报文的对端，以免会话表只增不减
        if (!m_kcpMsg.isClient) {
            // 时间必须重新取：步骤 1 刚把 lastActiveMs 刷成"现在"，拿循环开头那个旧的
            // nowMs 去减，无符号一绕回就成了天文数字，刚刚还在聊天的对端会被当场删掉
            unsigned long now = kcpClockMs();
            std::lock_guard<std::mutex> lock(m_lock);
            prunePeersLocked(now);
        }
    }
}

void KcpSocket::handleMessage(KcpPeer* peer, const char* data, int size)
{
    if (peer == nullptr || data == nullptr || size <= 0) {
        return;
    }
    char ip[INET_ADDRSTRLEN] = {0};
    inet_ntop(AF_INET, &peer->addr.sin_addr, ip, sizeof(ip));
    int port = ntohs(peer->addr.sin_port);
    notifyRecv(data, size, ip, port);

    if (!m_kcpMsg.isClient) {
        // 服务端把收到的原样射回同一个对端（客户端据此算 RTT）。
        // 两端都是服务端时这个开关是关的，否则两边会无限对打。
        if (!m_echoBack) {
            return;
        }
        std::lock_guard<std::mutex> lock(m_lock);
        ikcp_send(peer->kcp, data, size);
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
    {
        std::lock_guard<std::mutex> lock(m_lock);
        releasePeersLocked();
    }
    if (m_kcpMsg.sockfd != 0) {
        close(m_kcpMsg.sockfd);
        m_kcpMsg.sockfd = 0;
    }
    if (m_kcpMsg.pkcp != nullptr) {
        ikcp_release(m_kcpMsg.pkcp);
        m_kcpMsg.pkcp = nullptr;
    }
}
