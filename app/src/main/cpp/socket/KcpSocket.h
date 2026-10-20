#pragma once
// 子项目 socket/kcp（github.com/skywind3000/kcp），ikcp.h 就在同目录下
#include "kcp/ikcp.h"
#include <netinet/in.h>
#include <atomic>
#include <map>
#include <mutex>
#include <string>

#define KCP_MSG_LEN 1024

/** 收裸 UDP 用的缓冲：ikcp 一个报文最大是 mtu(1400)，比 KCP_MSG_LEN 还大，
    用 1024 的去收会把包截断，ikcp_input 拿到半包直接报 -1 */
#define KCP_UDP_BUF 2048

/**
 * 应用层包头：8 字节 = 序号(sn) + 发送时刻(ms)。
 * 服务端原样回射，客户端拿回来的包即可算出本次往返的 RTT。
 */
#define KCP_ECHO_HDR 8

/** 对端多久没有报文过来就回收它的会话 */
#define KCP_PEER_TIMEOUT_MS 60000

/**
 * 收到一条完整 KCP 消息时的回调。
 * data 不保证以 '\0' 结尾，按 len 取字节；peerIp / peerPort 是该报文的来源，
 * 服务端同时接多个对端时靠它区分是谁发的。
 */
typedef void (*KcpRecvCallback)(const char* data, int len, const char* peerIp, int peerPort, void* user);

/** 客户端收到服务端回射的包：sn 是序号，rttMs 是本次往返时延 */
typedef void (*KcpEchoCallback)(unsigned int sn, unsigned int rttMs, const char* data, int len, void* user);

typedef struct {
    unsigned char* ipstr;
    int port;
    /** 客户端专用的会话（服务端一份会话放一个 KcpPeer，不用这个字段） */
    ikcpcb* pkcp;
    int sockfd;
    struct sockaddr_in addr;
    /** 最近一次报文的来源：兼容既有字段，服务端仅将其视为「最后一个对端」 */
    struct sockaddr_in clientAddr;
    bool isClient;
} stKcpMsg;
/**
 * 一个对端 = 一个 KCP 会话。
 *
 * ikcp 的 output 回调只回传 ikcpcb* 和 create 时挂的 user，所以把 socket 与目标地址
 * 一起挂在 KcpPeer 上：服务端对着多个对端 sendto 时才知道该发往哪儿。
 * conv 也存一份：对端重启后新进程的 id 与序号都从零开始，靠它认出「同一个地址但是另一个人」。
 */
struct KcpPeer {
    ikcpcb* kcp = nullptr;
    struct sockaddr_in addr{};
    int sockfd = 0;
    unsigned long lastActiveMs = 0;
    /** 这份会话用的 conv，见 findOrCreatePeerLocked */
    IUINT32 conv = 0;
};

class KcpSocket {
public:
    KcpSocket();
    ~KcpSocket();

    /**
     * @param port   服务端监听端口 / 客户端目标端口
     * @param client true 为客户端
     * @param ip     客户端必填，服务端忽略（服务端绑 0.0.0.0，局域网任意网卡可入）
     */
    int init(int port, bool client = false, const char* ip = nullptr);
    void startClient();
    void startServer();
    /** 发一条消息：只进发送队列，真正发出去由循环里的 ikcp_update 完成（可在任意线程调用） */
    int send(const char* data, int len);
    /**
     * 服务端：按对端地址发一条消息（没打过交道的地址会自动建一份会话）；
     * 客户端忽略地址，发给自己那唯一一份会话。
     * 局域网消息就靠它：一个绑 0.0.0.0 的 socket 既能收谁的也能主动发给谁。
     */
    int sendTo(const char* ip, int port, const char* data, int len);
    /** 发一包「8 字节头(sn + 时间戳) + 载荷」：服务端会原样回射，客户端据此算 RTT */
    int sendEcho(const char* payload, int len);
    /** 服务端收到包后是否原样回射。两端都是服务端时必须关掉，否则会无限对打 */
    void setEchoEnabled(bool on);
    /** 最近一次 sendEcho 用的序号 */
    unsigned int lastSn() const;
    void setRecvCallback(KcpRecvCallback cb, void* user);
    void setEchoCallback(KcpEchoCallback cb, void* user);
    void stop();
    void destroy();
    /** 服务端当前还活着的对端数（客户端恒为 1） */
    int peerCount() const;

private:
    /** 客户端 / 服务端共用的 KCP 状态机循环：update → input → recv */
    void runLoop();
    /** 处理 ikcp_recv 解出来的一条应用层消息：上报 + 服务端回射 + 客户端算 RTT */
    void handleMessage(KcpPeer* peer, const char* data, int size);
    void notifyRecv(const char* data, int len, const char* peerIp, int peerPort);

    /** 按来源地址 + conv 找会话，没有就新建一个（调用方必须已持有 m_lock） */
    KcpPeer* findOrCreatePeerLocked(const struct sockaddr_in& from, IUINT32 conv);
    /** 按 ip:port 找会话，没有就新建一个（sendTo 主动发话时用；调用方必须已持有 m_lock） */
    KcpPeer* findOrCreatePeerByAddrLocked(const char* ip, int port);
    /** 回收超时未再发报文的对端（调用方必须已持有 m_lock） */
    void prunePeersLocked(unsigned long nowMs);
    void releasePeersLocked();

    std::atomic<bool> m_running;
    stKcpMsg m_kcpMsg;
    /** 本实例这一轮的 conv：每次 init() 换一个，对端重启后旧会话就能被认出来 */
    IUINT32 m_conv{0};
    /** kcp 本身不是线程安全的：send() 与循环里的 update/input/recv 互斥 */
    mutable std::mutex m_lock;
    /** 对端地址自己留一份：init() 传进来的指针生命周期不由本类托管 */
    char m_hostIp[64];
    KcpRecvCallback m_recvCb;
    void* m_recvUser;
    KcpEchoCallback m_echoCb;
    void* m_echoUser;
    /** 服务端是否把收到的包原样回射回去 */
    bool m_echoBack;
    /** 发送序号，sendEcho 每次自增（在 m_lock 里改） */
    unsigned int m_sn;
    /** 服务端：一个来源地址 + conv 一份会话 */
    std::map<std::string, KcpPeer*> m_peers;
    /** 客户端：自己的会话 */
    KcpPeer* m_self = nullptr;
};
