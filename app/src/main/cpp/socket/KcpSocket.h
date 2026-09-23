#pragma once
#include "kcp/ikcp.h"
#include <netinet/in.h>
#include <atomic>
#include <mutex>

#define KCP_MSG_LEN 1024

/**
 * 应用层包头：8 字节 = 序号(sn) + 发送时刻(ms)。
 * 服务端原样回射，客户端拿回来的包就能算出这一趟的 RTT。
 */
#define KCP_ECHO_HDR 8

/** 收到一条完整 KCP 消息时的回调：data 不保证以 '\0' 结尾，按 len 取字节 */
typedef void (*KcpRecvCallback)(const char* data, int len, void* user);

/** 客户端收到服务端回射的包：sn 是序号，rttMs 是这一趟的往返时延 */
typedef void (*KcpEchoCallback)(unsigned int sn, unsigned int rttMs, const char* data, int len, void* user);

typedef struct {
    unsigned char* ipstr;
    int port;
    ikcpcb* pkcp;
    int sockfd;
    struct sockaddr_in addr;
    struct sockaddr_in clientAddr;
    bool isClient;
} stKcpMsg;

class KcpSocket {
public:
    KcpSocket();
    ~KcpSocket();

    int init(int port, bool client = false, const char* ip = nullptr);
    void startClient();
    void startServer();
    /** 发一条消息：只进发送队列，真正发出去由循环里的 ikcp_update 完成（可在任意线程调用） */
    int send(const char* data, int len);
    /** 发一包「8 字节头(sn + 时间戳) + 载荷」：服务端会原样回射，客户端据此算 RTT */
    int sendEcho(const char* payload, int len);
    /** 最近一次 sendEcho 用的序号 */
    unsigned int lastSn() const;
    void setRecvCallback(KcpRecvCallback cb, void* user);
    void setEchoCallback(KcpEchoCallback cb, void* user);
    void stop();
    void destroy();

private:
    /** 客户端 / 服务端共用的 KCP 状态机循环：update → input → recv */
    void runLoop();
    /** 处理 ikcp_recv 解出来的一条应用层消息：上报 + 服务端回射 + 客户端算 RTT */
    void handleMessage(const char* data, int size);
    void notifyRecv(const char* data, int len);

    std::atomic<bool> m_running;
    stKcpMsg m_kcpMsg;
    /** kcp 本身不是线程安全的：send() 与循环里的 update/input/recv 互斥 */
    std::mutex m_lock;
    /** 对端地址自己留一份：init() 传进来的指针生命周期不由本类托管 */
    char m_hostIp[64];
    KcpRecvCallback m_recvCb;
    void* m_recvUser;
    KcpEchoCallback m_echoCb;
    void* m_echoUser;
    /** 发送序号，sendEcho 每次自增（在 m_lock 里改） */
    unsigned int m_sn;
};
