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

// 文件传输协议头 (64字节), 大端序(网络字节序)
// 与 MyAutomatic/LinxSrvc/Mac/Transfer 的 TransferEngine.h 逐字段对齐，两端才能互通
#pragma pack(push, 1)
struct FileHeader {
    uint8_t  magic[4];       // 魔数: "FTF\0"
    uint8_t  version;        // 协议版本: 1
    uint16_t cmd;            // 命令: 1=请求, 2=响应, 3=数据, 4=完成, 5=取消
    uint32_t fileNameLen;    // 文件名长度
    uint64_t fileSize;       // 文件总大小
    uint32_t chunkSize;      // 分片大小
    uint32_t chunkCount;     // 分片总数
    uint32_t currentChunk;   // 当前分片索引
    uint64_t transSize;      // 已传输大小
    uint8_t  reserved[25];   // 保留（补齐到 64 字节）
};
#pragma pack(pop)

// 协议头必须是 64 字节：字段自然长度只有 39 字节，靠 reserved 补齐。
// 一旦有人改动字段，这里会直接编译失败，避免线上出现"头长度对不上"的兼容性问题。
static_assert(sizeof(FileHeader) == 64, "FileHeader must be exactly 64 bytes");

// 传输进度回调
using ProgressCallback = std::function<void(uint64_t current, uint64_t total, const std::string& status)>;

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

    ClientSession() : sock(-1), clientPort(0), pendingFileSize(0), transSize(0), active(false) {}
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

private:
    std::map<int, std::unique_ptr<ClientSession>> m_sessions;
    mutable std::mutex m_mutex;
};

class FileMsgSocket {
public:
    static constexpr uint16_t CMD_REQUEST = 1;  // 请求文件传输
    static constexpr uint16_t CMD_RESPONSE = 2;  // 响应（接受/拒绝）
    static constexpr uint16_t CMD_DATA = 3;  // 数据分片
    static constexpr uint16_t CMD_COMPLETE = 4;  // 传输完成
    static constexpr uint16_t CMD_CANCEL = 5;  // 传输取消

    static constexpr uint32_t MAX_CHUNK_SIZE = 64 * 1024;  // 64KB per chunk
    static constexpr uint16_t DEFAULT_PORT = 8800;
    static constexpr int CONNECT_TIMEOUT_MS = 3000;    // 客户端 connect 超时
    static constexpr int RECV_POLL_TIMEOUT_MS = 5000;  // 服务端 poll 超时（避免 Nagle/RTT 导致文件名读取超时）
    static constexpr int RECV_RESP_TIMEOUT_MS = 5000;  // 客户端等待响应超时
    static constexpr int SEND_TIMEOUT_SEC = 30;        // 发送超时（对端一直不读时不会永久卡死）
    static constexpr uint32_t MAX_FILE_NAME_LEN = 1024; // 文件名长度上限（防恶意长度触发超大分配）

    FileMsgSocket();
    ~FileMsgSocket();

    // 服务器模式
    int startServer(unsigned short port = DEFAULT_PORT);
    void stopServer();

    // 客户端模式
    int connectToServer(const std::string& ip, unsigned short port);
    void disconnect();

    // 文件接收
    void setSavePath(const std::string& path);
    void setProgressCallback(ProgressCallback callback);

    // 文件发送
    int postLocalFile(const std::string& filePath);
    int requestFile(const std::string& ip, unsigned short port, const std::string& fileName);

    // 状态查询
    bool isConnected() const { return m_connected.load(); }
    bool isServerRunning() const { return m_serverRunning.load(); }
    int getClientCount() const { return (int)m_sessionMgr.size(); }

private:
    int createServerSocket(unsigned short port);
    int createClientSocket(const std::string& ip, unsigned short port);

    void fileServerProcess();
    void fileClientProcess();
    void clientHandler(int sock, const std::string& clientIp, unsigned short clientPort);

    int sendSliceData(int sock, const std::string& filePath, uint64_t fileSize);
    int sendHeader(int sock, const void* data, size_t len);
    int sendHeader(int sock, const FileHeader& header);
    int recvHeader(int sock, FileHeader& header);

private:
    int m_serverSock;
    int m_clientSock;
    std::atomic<int> m_currentSock;

    std::atomic<bool> m_serverRunning;
    std::atomic<bool> m_connected;
    std::atomic<bool> m_running;

    std::thread m_serverThread;
    std::thread m_receiveThread;
    // 每个客户端一个异步任务。已结束的任务会在 accept 时被回收，
    // 否则长时间运行的 server 会一直堆积线程对象（原实现只在 stopServer 才 join）。
    std::vector<std::future<void>> m_clientFutures;

    std::mutex m_sendMutex;
    std::mutex m_clientThreadMutex;

    std::string m_savePath;
    std::string m_serverIp;
    unsigned short m_serverPort;

    ProgressCallback m_progressCallback;

    ClientSessionMgr m_sessionMgr;
};

#endif //DEVICE2DEVICE_FileMsgSocket_H
