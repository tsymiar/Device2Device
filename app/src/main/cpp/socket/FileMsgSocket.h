//
// Created by Shenyrion on 2025.
//

#ifndef DEVICE2DEVICE_FileMsgSocket_H
#define DEVICE2DEVICE_FileMsgSocket_H

#include <string>
#include <cstdint>
#include <thread>
#include <mutex>
#include <atomic>
#include <functional>
#include <fstream>
#include <memory>
#include <map>
#include <vector>
#include <future>

#ifndef LOG_TAG
#define LOG_TAG "FileMsgSocket"
#endif

#include <utils/logging.h>

// 版本写进魔数第 3 字节，版本不同的端在魔数校验阶段就被拒。
static constexpr uint8_t PROTOCOL_VERSION = 1;
static constexpr uint8_t kProtocolMagic[4] = { 'F', 'T', 0xF0 + PROTOCOL_VERSION, 0x00 };

// 文件传输协议头 (64字节), 大端序(网络字节序)
// 与 MyAutomatic/LinxSrvc/Mac/Transfer 的 TransferEngine.h 逐字段对齐，两端才能互通。
// event 占用原 reserved[0]：线上格式不变，旧端读到的仍是 64 字节头，只是不解析该字段。
#pragma pack(push, 1)
struct FileHeader {
    uint8_t  magic[4];       // 魔数: 'F','T',0xF0+version,0x00
    uint8_t  version;        // 协议版本
    uint16_t cmd;            // 命令: 1=请求, 2=响应, 3=数据, 4=完成, 5=取消, 6=删除残留
    uint32_t fileNameLen;    // 文件名长度
    uint64_t fileSize;       // 文件总大小
    uint32_t chunkSize;      // 分片大小
    uint32_t chunkCount;     // 分片总数
    uint32_t currentChunk;   // 当前分片索引
    uint64_t transSize;      // 已传输大小
    uint8_t  event;          // 事件码 FILE_EVT_*（COMPLETE/CANCEL/PULL/RESP 时携带）
    uint8_t  reserved[24];   // 保留（补齐到 64 字节）
};
#pragma pack(pop)

// 协议头必须是 64 字节：改动字段会在此编译失败，避免两端"头长度对不上"。
static_assert(sizeof(FileHeader) == 64, "FileHeader must be exactly 64 bytes");

// 与 Mac/Transfer 的 FT_EVT_* 一一对应；UI 按事件码判定状态，不解析状态字符串。
static constexpr uint8_t FILE_EVT_NONE = 0;               // 常规进度刷新
static constexpr uint8_t FILE_EVT_CONNECTED = 1;
static constexpr uint8_t FILE_EVT_DISCONNECTED = 2;
static constexpr uint8_t FILE_EVT_RECEIVE_START = 3;      // 收到新的传输请求
static constexpr uint8_t FILE_EVT_TRANSFER_COMPLETE = 4;
static constexpr uint8_t FILE_EVT_TRANSFER_CANCELLED = 5;
static constexpr uint8_t FILE_EVT_REQUEST_PULL = 6;       // CMD_REQUEST 表示拉取而非推送
static constexpr uint8_t FILE_EVT_SEND_START = 7;         // 发送被对端接受，即将开始推流

// 传输进度回调：event 为 FILE_EVT_*，NONE 时仅表示进度刷新
using ProgressCallback = std::function<void(uint64_t current, uint64_t total,
                                            const std::string& status, uint8_t event)>;

// setter 与工作线程并发时用 shared_ptr 快照，避免 std::function 在被替换析构的同时被调用
class ThreadSafeProgressCallback {
public:
    void set(ProgressCallback cb)
    {
        auto next = cb ? std::make_shared<ProgressCallback>(std::move(cb)) : nullptr;
        std::lock_guard<std::mutex> lock(m_mutex);
        m_cb = std::move(next);
    }
    explicit operator bool() const { return static_cast<bool>(snapshot()); }
    void operator()(uint64_t current, uint64_t total,
                    const std::string& status, uint8_t event) const
    {
        auto cb = snapshot();
        if (cb && *cb) (*cb)(current, total, status, event);
    }

private:
    std::shared_ptr<ProgressCallback> snapshot() const
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        return m_cb;
    }
    mutable std::mutex m_mutex;
    std::shared_ptr<ProgressCallback> m_cb;
};

// 客户端会话结构 - 管理每个客户端的文件接收状态
struct ClientSession {
    int sock;
    std::string clientIp;
    unsigned short clientPort;
    std::ofstream recvFile;           // 接收文件流
    std::string pendingFileName;      // 待接收文件名
    uint64_t pendingFileSize;         // 待接收文件大小
    uint64_t transSize;              // 已传输大小
    std::atomic<bool> active;         // 会话是否活跃
    std::string currentFilePath;      // 本次实际落盘的完整路径（完成后回传给 UI）
    std::vector<char> chunkBuf;       // 分片接收缓冲，跨分片复用避免每片重新分配

    ClientSession() : sock(-1), clientPort(0), pendingFileSize(0), transSize(0), active(false) {}
};

// 设备快照：短连接断开后仍保留最近一次连接记录，UI 才能稳定显示设备
struct ClientInfo {
    std::string ip;
    unsigned short port = 0;
    bool online = false;
    uint64_t lastSeenMs = 0;
    // 会话正在接收的文件与进度；file 为空表示空闲（以 recvFile 是否打开为准）
    std::string transferringFile;
    uint64_t transferredBytes = 0;
    uint64_t totalBytes = 0;
};

// 客户端会话管理类
class ClientSessionMgr {
public:
    void addSession(int sock, const std::string& ip, unsigned short port);
    void removeSession(int sock);
    ClientSession* getSession(int sock);
    void forEachSession(const std::function<void(ClientSession&)>& func);
    void closeAllSockets();
    size_t size() const;
    // 按最近连接时间倒序返回设备快照（在线与最近离线均包含）
    std::vector<ClientInfo> recentClients() const;
    // 快照条目数（m_recent 大小）；调用方按它分配缓冲，用会话数会截断离线条目
    size_t recentCount() const;

private:
    std::map<int, std::unique_ptr<ClientSession>> m_sessions;
    std::vector<ClientInfo> m_recent;
    mutable std::mutex m_mutex;
};

class FileMsgSocket {
public:
    static constexpr uint16_t CMD_REQUEST = 1;  // 请求文件传输
    static constexpr uint16_t CMD_RESPONSE = 2;  // 响应（接受/拒绝）
    static constexpr uint16_t CMD_DATA = 3;  // 数据分片
    static constexpr uint16_t CMD_COMPLETE = 4;  // 传输完成
    static constexpr uint16_t CMD_CANCEL = 5;  // 传输取消
    static constexpr uint16_t CMD_DELETE = 6;  // 删除对端取消传输残留的半截文件

    // CMD_RESPONSE.event 的接受标记：fileSize 不再兼任拒绝状态，空文件也能被接受。
    static constexpr uint8_t RESP_ACCEPT = 1;

    // 256KB：局域网下 64KB 分片会被逐片 RTT 限住吞吐，与 Mac/Transfer 保持一致
    static constexpr uint32_t MAX_CHUNK_SIZE = 256 * 1024;
    static constexpr uint16_t DEFAULT_PORT = 8800;

    // LAN 发现：固定 UDP 端口只跑信标，通告的是 TCP 传输端口
    static constexpr uint16_t DISCOVERY_PORT = 8801;
    // 周期是本端行为（不改线上格式），1s 而非 3s：叠上 Wi-Fi 省电延迟才不至于体感很慢
    static constexpr int DISCOVERY_INTERVAL_MS = 1000;
    static constexpr const char* DISCOVERY_MAGIC = "FTF-DISCOVER";

    static constexpr int CONNECT_TIMEOUT_MS = 3000;    // 客户端 connect 超时
    static constexpr int RECV_POLL_TIMEOUT_MS = 5000;  // 服务端 poll 超时（避免 Nagle/RTT 导致文件名读取超时）
    static constexpr int RECV_RESP_TIMEOUT_MS = 5000;  // 客户端等待响应超时
    static constexpr int SEND_TIMEOUT_SEC = 30;        // 发送超时（对端一直不读时不会永久卡死）
    static constexpr uint32_t MAX_FILE_NAME_LEN = 1024; // 文件名长度上限（防恶意长度触发超大分配）

    FileMsgSocket();
    ~FileMsgSocket();

    // 服务器模式
    int startServer(unsigned short port = DEFAULT_PORT);
    /** 快速中断：只置停止标志、shutdown fd、让会话失效，不 join / 不等待任务。
     *  可在 UI 线程调用；真正的线程回收交给后台析构里的 stopServer()。 */
    void interrupt();
    void stopServer();

    // LAN 发现：startBroadcast 由 startServer 自动拉起，单独暴露给 UI 做开关
    int startBroadcast();
    void stopBroadcast();
    bool isBroadcasting() const { return m_broadcastRunning.load(); }
    void setDiscoveryEnabled(bool enabled);
    bool isDiscoveryEnabled() const { return m_discoveryEnabled.load(); }
    // 把广播/监听钉到指定网卡（"wlan0"/"eth0"）；空串 = 自动
    void setBindInterface(const std::string& ifname);
    // 主用局域网 IPv4（Wi-Fi/以太网），失败返回 ""
    static std::string resolveLocalIP();
    // 监听对端信标：回调在任意线程，参数为对端 IP 与 TCP 端口；返回 token 交 conceal() 停止
    std::shared_ptr<std::atomic<bool>> startDiscovery(
        std::function<void(const std::string& ip, unsigned short port)> onDiscovery);
    void conceal(std::shared_ptr<std::atomic<bool>> token);

    // 客户端模式
    int connectToServer(const std::string& ip, unsigned short port);
    void disconnect();

    // 取消当前传输，下个分片边界生效；发送侧会发 CMD_CANCEL 让对端登记半截文件
    void cancelTransfer();

    // 请求对端删除此前取消传输残留的半截文件（按原始文件名匹配，对端只删记录过的路径）
    int deletePartialFile(const std::string& fileName);

    // 文件接收
    void setSavePath(const std::string& path);
    void setProgressCallback(ProgressCallback callback);

    // ENOTCONN 重试策略：true = 指数退避（16 次、10ms 起翻倍）；false = 3 次固定 20ms 宽限
    void setBackoffEnabled(bool enabled);
    bool isBackoffEnabled() const { return m_backoffEnabled.load(); }

    // 文件发送
    int postLocalFile(const std::string& filePath);

    // 拉取：连对端取回文件并阻塞至完成；对端找不到同名普通文件时本端返回 -5
    int requestFile(const std::string& ip, unsigned short port, const std::string& fileName);

    // 状态查询
    bool isConnected() const { return m_connected.load(); }
    // 当前传输字节数（阻塞发送期间 UI 也能读到实时值）
    uint64_t transferCurrent() const { return m_transferCurrent.load(); }
    uint64_t transferTotal() const { return m_transferTotal.load(); }
    bool isServerRunning() const { return m_serverRunning.load(); }
    // 与 getClients() 同源：快照条数（含离线设备），而非当前活跃会话数
    int getClientCount() const { return (int)m_sessionMgr.recentCount(); }
    std::vector<ClientInfo> getClients() const { return m_sessionMgr.recentClients(); }

private:
    int createServerSocket(unsigned short port);
    int createClientSocket(const std::string& ip, unsigned short port);

    void fileServerProcess();
    void clientHandler(int sock, const std::string& clientIp, unsigned short clientPort);

    // push 用 m_connected 作运行标志，服务端代发用 session->active；代发传 nullptr 不消费取消
    int sendSliceData(int sock, const std::string& filePath, uint64_t fileSize,
                      const std::atomic<bool>& runFlag,
                      std::shared_ptr<std::atomic<bool>> cancelFlag);
    // 拉取模式的客户端接收循环：与服务端 CMD_DATA 相同的分片语义落盘
    int receivePullData(int sock, const std::string& fileName, uint64_t fileSize,
                        std::shared_ptr<std::atomic<bool>> cancelFlag);
    // 为一次客户端传输创建独立取消标志；weak_ptr 不延寿，传输结束即自动过期。
    std::shared_ptr<std::atomic<bool>> beginTransfer();
    int sendHeader(int sock, const void* data, size_t len);
    int sendHeader(int sock, const FileHeader& header);
    int recvHeader(int sock, FileHeader& header);

    // LAN 发现后台循环（各跑在自己的线程上）。
    void broadcastLoop();
    void discoveryLoop(std::shared_ptr<std::atomic<bool>> token);

    // 停止广播：interrupt() 传 false 只收线程不等待，stopServer() 传 true 负责 join。
    void stopBroadcastInternal(bool joinThread);

    // 网卡绑定：选/通告地址，并把 socket 钉到 m_bindInterface 上。
    std::string resolveBindIP() const;
    std::string bindInterfaceSnapshot() const;
    static std::string interfaceIPv4(const std::string& ifname);
    bool bindSocketToInterface(int sock) const;

    std::string savePath() const;

private:
    int m_serverSock;
    int m_clientSock;

    std::atomic<bool> m_serverRunning;
    std::atomic<bool> m_connected;
    std::atomic<bool> m_running;
    std::atomic<bool> m_backoffEnabled;   // ENOTCONN 是否走指数退避（默认 false）
    std::atomic<uint64_t> m_transferCurrent{ 0 };   // 传输进度（字节）
    std::atomic<uint64_t> m_transferTotal{ 0 };

    // 广播默认开，UI 可关且运行中即时生效
    std::atomic<bool> m_discoveryEnabled{ true };
    std::atomic<bool> m_broadcastRunning{ false };
    std::thread m_broadcastThread;
    std::atomic<int> m_broadcastSock{ -1 };
    std::string m_announceIp;        // 信标里通告的局域网 IP
    unsigned short m_announcePort{ 0 };
    std::mutex m_broadcastMutex;     // 串行化 start/stopBroadcast

    // 对端信标监听
    std::atomic<bool> m_discoveryRunning{ false };
    std::thread m_discoveryThread;
    std::atomic<int> m_discoverySock{ -1 };
    mutable std::mutex m_discoveryMutex;
    std::function<void(const std::string&, unsigned short)> m_discoveryCb;
    std::shared_ptr<std::atomic<bool>> m_discoveryToken;
    std::mutex m_discoveryLifecycleMutex;   // 串行化 startDiscovery/conceal

    // 广播/发现钉住的网卡（"" = 自动）
    std::string m_bindInterface;
    mutable std::mutex m_bindMutex;   // 保护 m_bindInterface

    // 每传输一个独立取消标志：并发代发不会互相消费，新传输也清不掉刚发出的取消
    std::mutex m_cancelMutex;
    std::weak_ptr<std::atomic<bool>> m_activeCancel;

    // CMD_CANCEL 时记录的半截文件：原始文件名 -> 磁盘完整路径，等待对端 CMD_DELETE
    std::map<std::string, std::string> m_cancelledPartials;
    std::mutex m_partialMutex;

    std::thread m_serverThread;
    // 每个客户端一个异步任务，结束的在 accept 时回收，避免长期运行堆积线程对象
    std::vector<std::future<void>> m_clientFutures;

    std::mutex m_sendMutex;
    std::mutex m_clientThreadMutex;
    mutable std::mutex m_stateMutex;   // 保护 m_savePath / m_serverIp / m_serverPort / 回调

    std::string m_savePath;
    std::string m_serverIp;
    unsigned short m_serverPort;

    ThreadSafeProgressCallback m_progressCallback;

    ClientSessionMgr m_sessionMgr;
};

#endif //DEVICE2DEVICE_FileMsgSocket_H
