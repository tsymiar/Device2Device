#ifndef DEVICE2DEVICE_UdpSocket_H
#define DEVICE2DEVICE_UdpSocket_H

#include <arpa/inet.h>
#include <string>

struct NetProtocol {
    uint64_t id;
    uint64_t size;
    uint64_t slice;
    uint64_t s_idx;
    uint64_t s_size;
    uint64_t offset;
    uint64_t remain;
};

/**
 * UDP 收发。
 *
 * 监听端口由构造参数决定：open() 绑的就是 m_port，startUdpServer 传进来的 port 落在哪就在哪听。
 *
 * 两套用法：
 *  - 一步到位：`UdpSocket(port).Receiver(buf, len, cb)` 一直收到出错；
 *              `UdpSocket(ip, port).Sender(data, len)` 发一包（带 NetProtocol 头、超长自动分片）。
 *  - 自己控循环：`open()` 之后用 `sendTo()` / `receive()`，
 *              可以开广播（设备发现要靠它）、设置收包超时（否则无法退出）、获取对端地址。
 */
class UdpSocket {
public:
    UdpSocket();

    /** 收：绑这个端口 */
    explicit UdpSocket(int);

    /** 发：默认目标地址 */
    UdpSocket(const std::string &, int);

    ~UdpSocket();

    UdpSocket(const UdpSocket&) = delete;
    UdpSocket& operator=(const UdpSocket&) = delete;

    /** 收发的本机端口。改了端口要重新 open() 才生效 */
    void setPort(int port);
    int port() const;

    /**
     * 建 socket 并绑到 m_port（INADDR_ANY），同时把 SO_REUSEADDR 设上。
     * 广播与超时是在 socket 上生效的选项，必须在 open() 之前设。
     */
    int open();
    void closeSocket();
    bool isOpen() const;

    /**
     * 允许发广播。
     * 往 255.255.255.255 或网段广播地址 sendto 时不开这个会直接报 EACCES。
     */
    void enableBroadcast(bool on);

    /** 加入组播组（仅接收需要；发往组播地址无需此步）。open() 之前设，join 在 open() 里做 */
    void setMulticastGroup(const std::string& group);

    /** 收包超时（毫秒）。0 = 一直堵在 recvfrom —— 保持老行为 */
    void setRecvTimeoutMs(int ms);

    /** 让循环停下来：Receiver() 会退出，receive() 直接返回 -1 */
    void stop();
    /** 清掉 stop 标志，同一个对象可以再开一轮 */
    void restart();

    /** 老接口：往构造时给的 ip:port 发一包（带 NetProtocol 头），发完即关 */
    int Sender(const char *, size_t);

    /** 往指定目标发一包裸数据（不带 NetProtocol 头）。socket 需先 open() */
    int sendTo(const char *ip, int port, const void *data, size_t length);

    /**
     * 收一包。
     * @return >0 收到的字节数（buf 已补 '\0'）；0 超时没收到；-1 出错或已 stop
     */
    int receive(char *buf, int len, std::string *peerIp = nullptr, int *peerPort = nullptr);

    /** 老接口：循环收到出错为止，正文（去掉 NetProtocol 头）交给 callback */
    int Receiver(char *, int, void(*)(char*) = nullptr);

private:
    int SendBySlice(const char *, size_t);

    int m_socket = -1;
    std::string m_ip = {};
    int m_port = 0;
    /** stop 标志：置上之后 Receiver 的循环退出、receive() 不再读 */
    bool m_flag = false;
    bool m_broadcast = false;
    std::string m_multicastGroup;
    bool m_multicast = false;
    int m_recvTimeoutMs = 0;
    bool m_bound = false;
    NetProtocol protocol{};
    struct sockaddr_in m_peer{};
    const uint32_t SLICE_LEN = 1024;
    const uint32_t m_proSize = sizeof(NetProtocol);
    const uint32_t m_addLen = sizeof(struct sockaddr);
};

#endif //DEVICE2DEVICE_UdpSocket_H
