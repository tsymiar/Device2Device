//
// Created by Shenyrion on 2025.
//
// 协议与状态机对齐 MyAutomatic/LinxSrvc/Mac/Transfer 的 TransferEngine：
//  - 64 字节协议头、统一大端（网络字节序）传输
//  - 文件名做长度校验 + 路径净化（防 DoS / 路径穿越）
//  - 分片大小按「剩余字节」计算（修正空文件与末片越界写坏文件的问题）
//  - 客户端任务用 future 托管并在 accept 时回收（长跑不再堆积线程对象）
//

#include "FileMsgSocket.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <netdb.h>
#include <poll.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <cstring>
#include <cerrno>
#include <chrono>
#include <algorithm>
#include <memory>
#include <vector>
#include <map>
#include <ctime>
#include <time.h>   // localtime_r / strftime（POSIX；<ctime> 不保证把它们放进全局命名空间）

// --- Helper: fill FileHeader with common defaults ---
static inline void fillHeader(FileHeader& h, uint16_t cmd) {
    memset(&h, 0, sizeof(h));
    memcpy(h.magic, "FTF\0", 4);
    h.version = 1;
    h.cmd = cmd;
}

// --- 字节序转换 ---
// 协议头统一按大端(网络字节序)传输，否则两端字节序不同的设备之间根本解不开。
static uint64_t hton64(uint64_t v)
{
    uint32_t hi = htonl((uint32_t)(v >> 32));
    uint32_t lo = htonl((uint32_t)(v & 0xFFFFFFFFu));
    return ((uint64_t)lo << 32) | (uint64_t)hi;
}

static void headerToNet(FileHeader& h)
{
    h.cmd          = htons(h.cmd);
    h.fileNameLen  = htonl(h.fileNameLen);
    h.fileSize     = hton64(h.fileSize);
    h.chunkSize    = htonl(h.chunkSize);
    h.chunkCount   = htonl(h.chunkCount);
    h.currentChunk = htonl(h.currentChunk);
    h.transSize    = hton64(h.transSize);
}

/// 转换是对称的，网络序 -> 主机序复用同一个函数。
static void headerToHost(FileHeader& h) { headerToNet(h); }

// --- 文件名安全化 ---
// 客户端给的文件名不可信：必须剥掉所有路径成分，
// 否则 "../../.ssh/authorized_keys" 能写到进程有权限的任意位置。
static std::string sanitizeFileName(const std::string& name)
{
    size_t pos = name.find_last_of("/\\");
    std::string base = (pos != std::string::npos) ? name.substr(pos + 1) : name;

    std::string out;
    out.reserve(base.size());
    for (unsigned char c : base) {
        if (c < 32 || c == 127 || c == '/' || c == '\\' || c == ':') {
            out += '_';
        } else {
            out += (char)c;
        }
    }
    if (out.empty() || out == "." || out == "..") {
        out = "received_file";
    }
    if (out.size() > 255) {
        out = out.substr(0, 255);
    }
    return out;
}

// --- 可靠的 send 辅助函数 ---
// send() 不保证一次发送所有数据（只返回实际发送量）。
// sendAll 循环调用 send() 直到全部发送或出错，与 recvAll 对称。
// 返回值: 0 成功，-1 发送失败
static int sendAll(int sock, const void* buf, size_t len)
{
    size_t total = 0;
    while (total < len) {
#ifdef MSG_NOSIGNAL
        ssize_t ret = send(sock, (const char*)buf + total, len - total, MSG_NOSIGNAL);
#else
        ssize_t ret = send(sock, (const char*)buf + total, len - total, 0);
#endif
        if (ret < 0) {
            if (errno == EINTR) {
                continue;  // 被信号中断，重试
            }
            // EAGAIN/EWOULDBLOCK = 触发了 SO_SNDTIMEO（对端迟迟不读）。
            // 这里绝不能 continue：阻塞 socket 上会退化成无限忙等，
            // 发送方永久卡死，UI 的 isBusy 再也回不来。
            return -1;
        }
        if (ret == 0) {
            return -1;  // send 返回 0 表示连接关闭
        }
        total += (size_t)ret;
    }
    return 0;
}

// --- 可靠的 recv 辅助函数 ---
// 使用 poll() 检测连接状态，以手动读循环替代 MSG_WAITALL，避免 SO_RCVTIMEO + MSG_WAITALL 的兼容性问题
// 返回值: >0 成功（应等于len），0 客户端断开(EOF/POLLHUP)，-1 超时（可重试），-2 其他错误
static int recvAll(int sock, void* buf, size_t len, int timeoutMs)
{
    struct pollfd pfd {};
    pfd.fd = sock;
    pfd.events = POLLIN;

    size_t total = 0;
    while (total < len) {
        int pr = poll(&pfd, 1, timeoutMs);
        if (pr == 0) {
            return -1;  // 超时，无数据
        }
        if (pr < 0) {
            if (errno == EINTR) continue;   // 信号中断，重试
            return -2;  // poll 错误
        }
        // 先读数据再判断断开，避免 POLLIN|POLLHUP 同时设置时丢弃数据
        if (pfd.revents & POLLIN) {
            ssize_t ret = recv(sock, (char*)buf + total, len - total, 0);
            if (ret == 0) {
                return 0;   // EOF，客户端正常关闭
            }
            if (ret < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
                    continue;  // 临时错误，重试
                }
                return -2;  // recv 错误
            }
            total += ret;
            continue;
        }
        if (pfd.revents & (POLLHUP | POLLERR)) {
            return 0;   // 客户端断开（缓冲区已空）
        }
        // 其他意外事件，继续 poll
    }
    return (int)total;
}

// --- ClientSessionMgr ---

void ClientSessionMgr::addSession(int sock, const std::string& ip, unsigned short port)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    std::unique_ptr<ClientSession> session(new ClientSession());
    session->sock = sock;
    session->clientIp = ip;
    session->clientPort = port;
    session->active = true;
    m_sessions[sock] = std::move(session);
    LOGI("Client session added: %s:%d (total: %zu)", ip.c_str(), port, m_sessions.size());
}

void ClientSessionMgr::removeSession(int sock)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_sessions.find(sock);
    if (it != m_sessions.end()) {
        if (it->second && it->second->recvFile.is_open()) {
            it->second->recvFile.close();
        }
        m_sessions.erase(it);
        LOGI("Client session removed (total: %zu)", m_sessions.size());
    }
}

ClientSession* ClientSessionMgr::getSession(int sock)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_sessions.find(sock);
    if (it != m_sessions.end()) {
        return it->second.get();
    }
    return nullptr;
}

void ClientSessionMgr::forEachSession(const std::function<void(ClientSession&)>& func)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& pair : m_sessions) {
        if (pair.second) {
            func(*pair.second);
        }
    }
}

void ClientSessionMgr::closeAllSockets()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& pair : m_sessions) {
        if (pair.second && pair.second->sock >= 0) {
            LOGI("closing client %s:%d (fd=%d)",
                pair.second->clientIp.c_str(), pair.second->clientPort, pair.second->sock);
            shutdown(pair.second->sock, SHUT_RDWR);
            close(pair.second->sock);
            pair.second->sock = -1;
        }
    }
}

size_t ClientSessionMgr::size() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_sessions.size();
}

// --- FileMsgSocket ---

FileMsgSocket::FileMsgSocket()
    : m_serverSock(-1)
    , m_clientSock(-1)
    , m_currentSock(-1)
    , m_serverRunning(false)
    , m_connected(false)
    , m_running(false)
    , m_serverPort(0)
{}

FileMsgSocket::~FileMsgSocket()
{
    stopServer();
    disconnect();
}

void FileMsgSocket::setSavePath(const std::string& path)
{
    m_savePath = path;
}

void FileMsgSocket::setProgressCallback(ProgressCallback callback)
{
    m_progressCallback = callback;
}

int FileMsgSocket::createServerSocket(unsigned short port)
{
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        LOGE("socket() failed (%s)", strerror(errno));
        return -1;
    }

    int opt = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &opt, sizeof(opt));
#ifdef SO_NOSIGPIPE
    setsockopt(sock, SOL_SOCKET, SO_NOSIGPIPE, &opt, sizeof(opt));
#endif

    struct sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("bind() failed (%s)", strerror(errno));
        close(sock);
        return -2;
    }

    if (listen(sock, 5) < 0) {
        LOGE("listen() failed (%s)", strerror(errno));
        close(sock);
        return -3;
    }

    LOGI("FileMsgSocket server listening on port %d", port);
    return sock;
}

int FileMsgSocket::createClientSocket(const std::string& ip, unsigned short port)
{
    LOGI("connecting to %s:%d ...", ip.c_str(), port);
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        LOGE("socket() failed (%s)", strerror(errno));
        return -1;
    }

    // Keepalive to detect broken connections early; disable Nagle for low-latency sends
    int opt = 1;
    setsockopt(sock, SOL_SOCKET, SO_KEEPALIVE, &opt, sizeof(opt));
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &opt, sizeof(opt));
#ifdef SO_NOSIGPIPE
    setsockopt(sock, SOL_SOCKET, SO_NOSIGPIPE, &opt, sizeof(opt));
#endif

    // 发送超时：对端一直不读时 send() 会返回 EAGAIN 而不是永久阻塞
    struct timeval sndTv;
    sndTv.tv_sec  = SEND_TIMEOUT_SEC;
    sndTv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &sndTv, sizeof(sndTv));

    struct sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);

    if (inet_pton(AF_INET, ip.c_str(), &addr.sin_addr) <= 0) {
        // 主机名解析走 getaddrinfo（与 Transfer 实现一致）：gethostbyname 已废弃，
        // 且返回的 hostent 指向静态内存，多线程同时解析会互相踩。
        struct addrinfo hints{};
        struct addrinfo* res = nullptr;
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_STREAM;
        int ga = getaddrinfo(ip.c_str(), nullptr, &hints, &res);
        if (ga != 0 || res == nullptr) {
            LOGE("getaddrinfo(%s) failed: %s", ip.c_str(), gai_strerror(ga));
            close(sock);
            return -2;
        }
        memcpy(&addr.sin_addr, &((struct sockaddr_in*)res->ai_addr)->sin_addr,
            sizeof(addr.sin_addr));
        freeaddrinfo(res);
    }

    // ── 非阻塞 connect + poll 超时 ──
    // 阻塞 connect 在服务端不可达时卡死约 75s（TCP 默认超时），用户体验极差。
    // 改用 O_NONBLOCK + poll(POLLOUT) 实现超时控制。
    int origFlags = fcntl(sock, F_GETFL, 0);
    if (origFlags < 0) {
        LOGE("fcntl(F_GETFL) failed (%s)", strerror(errno));
        close(sock);
        return -3;
    }
    fcntl(sock, F_SETFL, origFlags | O_NONBLOCK);

    int cr = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
    if (cr < 0 && errno != EINPROGRESS) {
        LOGE("connect() failed (%s)", strerror(errno));
        close(sock);
        return -3;
    }

    if (cr < 0) {  // EINPROGRESS — 等待完成或超时
        struct pollfd pfd{};
        pfd.fd = sock;
        pfd.events = POLLOUT;
        int pr = poll(&pfd, 1, CONNECT_TIMEOUT_MS);
        if (pr == 0) {
            LOGE("connect() to %s:%u timed out after %d ms", ip.c_str(), port, CONNECT_TIMEOUT_MS);
            close(sock);
            return -3;
        }
        if (pr < 0) {
            LOGE("poll() failed (%s)", strerror(errno));
            close(sock);
            return -3;
        }
        // 确认连接真的建立成功
        int sockErr = 0;
        socklen_t soLen = sizeof(sockErr);
        if (getsockopt(sock, SOL_SOCKET, SO_ERROR, &sockErr, &soLen) < 0 || sockErr != 0) {
            LOGE("connect() failed: SO_ERROR=%d/%s", sockErr, sockErr ? strerror(sockErr) : "getsockopt error");
            close(sock);
            return -3;
        }
    }

    // 恢复阻塞模式
    fcntl(sock, F_SETFL, origFlags);

    LOGI("FileMsgSocket connected to %s:%d", ip.c_str(), port);
    return sock;
}

int FileMsgSocket::startServer(unsigned short port)
{
    LOGI("starting server on port %d ...", port);

    if (m_serverRunning.load()) {
        LOGW("server already running");
        return 0;
    }

    m_serverSock = createServerSocket(port);
    if (m_serverSock < 0) {
        LOGE("createServerSocket failed (ret=%d)", m_serverSock);
        return m_serverSock;
    }

    m_serverRunning.store(true);
    m_running.store(true);

    LOGI("starting accept thread ...");
    m_serverThread = std::thread(&FileMsgSocket::fileServerProcess, this);
    LOGI("server started successfully on port %d (fd=%d)", port, m_serverSock);
    return 0;
}

void FileMsgSocket::stopServer()
{
    m_serverRunning.store(false);
    m_running.store(false);

    // 关闭所有客户端 socket，立即中断其阻塞的 recv() 调用
    m_sessionMgr.closeAllSockets();

    if (m_serverSock >= 0) {
        // shutdown 立即中断阻塞的 accept() 调用
        shutdown(m_serverSock, SHUT_RDWR);
        close(m_serverSock);
        m_serverSock = -1;
    }

    if (m_serverThread.joinable()) {
        m_serverThread.join();
    }

    // 安全等待所有客户端任务退出：必须在本对象析构前做完，
    // 否则还在跑的 clientHandler 会访问已析构的 this。
    {
        std::lock_guard<std::mutex> lock(m_clientThreadMutex);
        for (std::future<void>& f : m_clientFutures) {
            if (f.valid()) {
                f.wait();
            }
        }
        m_clientFutures.clear();
    }

    LOGI("FileMsgSocket Server Exit");
}

int FileMsgSocket::connectToServer(const std::string& ip, unsigned short port)
{
    LOGI("connecting to %s:%d ...", ip.c_str(), port);

    if (m_connected.load()) {
        LOGI("already connected, disconnecting first");
        disconnect();
    }

    m_clientSock = createClientSocket(ip, port);
    if (m_clientSock < 0) {
        LOGE("createClientSocket failed (ret=%d)", m_clientSock);
        return m_clientSock;
    }

    m_currentSock.store(m_clientSock);
    m_serverIp = ip;
    m_serverPort = port;
    m_connected.store(true);
    m_running.store(true);

    // 客户端模式下无需后台接收线程 — 响应由 postLocalFile 同步处理
    LOGI("connected successfully to %s:%d (fd=%d)", ip.c_str(), port, m_clientSock);
    return 0;
}

void FileMsgSocket::disconnect()
{
    m_connected.store(false);
    m_running.store(false);

    if (m_clientSock >= 0) {
        LOGI("disconnect: closing connection to %s:%d (fd=%d)",
            m_serverIp.c_str(), m_serverPort, (int)m_clientSock);
        close(m_clientSock);
        m_clientSock = -1;
    }

    if (m_receiveThread.joinable()) {
        m_receiveThread.join();
    }

    LOGI("FileMsgSocket disconnected");
}

void FileMsgSocket::fileServerProcess()
{
    struct sockaddr_in clientAddr {};
    socklen_t addrLen = sizeof(clientAddr);

    while (m_serverRunning.load()) {
        int clientSock = accept(m_serverSock, (struct sockaddr*)&clientAddr, &addrLen);
        if (clientSock < 0) {
            // accept 已由 stopServer() 中的 shutdown() 中断
            if (m_serverRunning.load()) {
                LOGE("accept() failed (%s)", strerror(errno));
            }
            break;
        }

        char ipStr[INET_ADDRSTRLEN];
        unsigned short clientPort = ntohs(clientAddr.sin_port);
        inet_ntop(AF_INET, &clientAddr.sin_addr, ipStr, sizeof(ipStr));

        // 丢弃"到达即死"的连接：accept() 可能返回对端已经 abort 的连接，
        // 这种 fd 上 recv 只会报 EOF / ENOTCONN，交给 clientHandler 纯属白跑一趟。
        // 用非阻塞 MSG_PEEK 探一个字节：不消耗已到达的数据，空闲连接返回 EAGAIN 属正常。
        int probeFlags = fcntl(clientSock, F_GETFL, 0);
        if (probeFlags >= 0) {
            fcntl(clientSock, F_SETFL, probeFlags | O_NONBLOCK);
        }
        char probeByte = 0;
        ssize_t probe = recv(clientSock, &probeByte, 1, MSG_PEEK);
        int probeErrno = errno;   // 必须在还原 flags 前快照，后续 syscall 会覆盖 errno
        if (probeFlags >= 0) {
            fcntl(clientSock, F_SETFL, probeFlags);
        }
        if (probe == 0) {
            LOGW("client %s:%d already closed (EOF) — dropping fd=%d", ipStr, clientPort, clientSock);
            close(clientSock);
            continue;
        }
        if (probe < 0 && probeErrno != EAGAIN && probeErrno != EWOULDBLOCK && probeErrno != EINTR) {
            LOGW("client %s:%d dead on arrival (errno=%d/%s) — dropping fd=%d",
                ipStr, clientPort, probeErrno, strerror(probeErrno), clientSock);
            close(clientSock);
            continue;
        }

        LOGI("FileMsgSocket client connected from %s:%d", ipStr, clientPort);

        // 禁用 Nagle 确保响应等小包立即发出
        int opt = 1;
        setsockopt(clientSock, IPPROTO_TCP, TCP_NODELAY, &opt, sizeof(opt));
#ifdef SO_NOSIGPIPE
        setsockopt(clientSock, SOL_SOCKET, SO_NOSIGPIPE, &opt, sizeof(opt));
#endif
        // 同上：给已连接 socket 也设发送超时
        struct timeval sndTv;
        sndTv.tv_sec  = SEND_TIMEOUT_SEC;
        sndTv.tv_usec = 0;
        setsockopt(clientSock, SOL_SOCKET, SO_SNDTIMEO, &sndTv, sizeof(sndTv));

        // 为每个客户端创建独立任务处理
        std::lock_guard<std::mutex> lock(m_clientThreadMutex);
        // 先回收已结束的任务：否则长跑时这里会一直堆积线程对象
        m_clientFutures.erase(
            std::remove_if(m_clientFutures.begin(), m_clientFutures.end(),
                [](std::future<void>& f) {
                    return f.wait_for(std::chrono::seconds(0)) == std::future_status::ready;
                }),
            m_clientFutures.end());
        m_clientFutures.push_back(std::async(std::launch::async,
            &FileMsgSocket::clientHandler, this, clientSock, std::string(ipStr), clientPort));
    }
}

void FileMsgSocket::fileClientProcess()
{
    // 客户端模式下此线程原本只做空转 sleep，无实际接收逻辑。
    // 响应和超时均由 postLocalFile 所在的调用线程同步处理。
    // 此函数保留以兼容头文件声明，connectToServer 不再启动此线程。
    LOGI("fileClientProcess: stub — no background receive needed in client mode");
}

void FileMsgSocket::clientHandler(int sock, const std::string& clientIp, unsigned short clientPort)
{
    LOGI("clientHandler START fd=%d from %s:%u", sock, clientIp.c_str(), clientPort);

    // 确保 socket 处于阻塞模式：recvAll 是 poll + 阻塞 recv，
    // 万一这个 fd 继承了 O_NONBLOCK，recv 会立刻返回 EAGAIN，被误判成"对端超时"。
    int flags = fcntl(sock, F_GETFL, 0);
    if (flags < 0) {
        LOGE("clientHandler fd=%d fcntl(F_GETFL) failed (%s) — fd invalid, exiting",
            sock, strerror(errno));
        close(sock);
        return;
    }
    if (flags & O_NONBLOCK) {
        LOGW("clientHandler fd=%d was O_NONBLOCK, forcing blocking mode", sock);
        fcntl(sock, F_SETFL, flags & ~O_NONBLOCK);
    }

    // 添加会话到管理器
    m_sessionMgr.addSession(sock, clientIp, clientPort);
    ClientSession* session = m_sessionMgr.getSession(sock);
    if (!session) {
        close(sock);
        return;
    }

    // 通知上层：客户端已连接
    if (m_progressCallback) {
        m_progressCallback(0, 0, "Client connected from " + clientIp + ":" + std::to_string(clientPort));
    }

    std::string statusPrefix = "[" + clientIp + ":" + std::to_string(clientPort) + "] ";

    // ── ENOTCONN / EBADF 处理 ──
    // 一条已 accept 的 socket 在 recv 时返回 ENOTCONN（首次 SO_ERROR 还可能是 EBADF），
    // 基本等于对端已经 RST/abort —— 这不是"系统还没准备好"那种能自愈的瞬时状态。
    // 所以只给极短宽限，宽限用尽就判定连接已死并关闭；按指数退避重试十几次只会把
    // 这个处理线程卡住几分钟（还会刷满日志），并不能把它救回来。
    static constexpr int RECV_ERR_RETRY_MAX = 3;        // 仅覆盖极少数平台的瞬时异常
    static constexpr int RECV_ERR_RETRY_DELAY_MS = 20;  // 固定 20ms，不做指数退避
    int errRetries = 0;

    while (m_running.load() && session->active.load()) {
        FileHeader header{};
        int ret = recvAll(sock, &header, sizeof(header), RECV_POLL_TIMEOUT_MS);
        if (ret <= 0) {
            if (ret == -1) {
                // 超时，回到 while 检查 m_running 是否被 stopServer() 置为 false
                errRetries = 0;
                continue;
            }
            if (ret == -2) {
                if (errRetries < RECV_ERR_RETRY_MAX) {
                    ++errRetries;
                    LOGW("%s recv header ENOTCONN/EBADF, retry %d/%d (peer likely gone)",
                        statusPrefix.c_str(), errRetries, RECV_ERR_RETRY_MAX);
                    std::this_thread::sleep_for(
                        std::chrono::milliseconds(RECV_ERR_RETRY_DELAY_MS));
                    continue;
                }
                // 宽限用尽：连接确已死亡，按对端断开处理
                LOGW("%s recv header ENOTCONN persists after %d retries — peer aborted, closing (fd=%d)",
                    statusPrefix.c_str(), RECV_ERR_RETRY_MAX, sock);
                break;
            }
            if (ret == 0) {
                LOGI("%s client disconnected (EOF/POLLHUP)", statusPrefix.c_str());
            } else {
                LOGW("%s recv header failed(%d) — retries exhausted", statusPrefix.c_str(), ret);
            }
            break;
        }
        errRetries = 0;   // 成功读到数据，重置退避计数

        // 收到的头是网络序，先转回主机序再解析
        headerToHost(header);

        // 验证魔数
        if (memcmp(header.magic, "FTF\0", 4) != 0) {
            LOGE("%s invalid magic number: 0x%08x, client disconnected", statusPrefix.c_str(), *(uint32_t*)header.magic);
            break;
        }

        LOGI("%s Received command: %d", statusPrefix.c_str(), header.cmd);
        switch (header.cmd) {
        case CMD_REQUEST: {
            // 文件名长度必须校验：客户端可以伪造一个 4GB 的 fileNameLen，
            // 直接拿它构造 std::string 会把进程内存打爆（DoS）。
            if (header.fileNameLen == 0 || header.fileNameLen > MAX_FILE_NAME_LEN) {
                LOGE("%s invalid fileNameLen=%u (max %u) — closing",
                    statusPrefix.c_str(), header.fileNameLen, MAX_FILE_NAME_LEN);
                session->active.store(false);
                break;
            }

            // 读取文件名
            std::string fileName(header.fileNameLen, '\0');
            int r = recvAll(sock, &fileName[0], header.fileNameLen, RECV_POLL_TIMEOUT_MS);
            if (r <= 0) {
                if (r == 0) {
                    LOGI("%s client disconnected while reading filename", statusPrefix.c_str());
                } else {
                    LOGE("%s recv filename timeout — protocol desync, closing", statusPrefix.c_str());
                }
                session->active.store(false);
                break;
            }

            // 文件名来自对端，先剥掉路径成分再落盘（防路径遍历）
            std::string safeName = sanitizeFileName(fileName);
            LOGI("%s FileMsgSocket incoming request: %s (%llu bytes)",
                statusPrefix.c_str(), safeName.c_str(), (unsigned long long)header.fileSize);

            session->pendingFileName = safeName;
            session->pendingFileSize = header.fileSize;
            session->transSize = 0;

            // 发送接受响应
            FileHeader response{};
            fillHeader(response, CMD_RESPONSE);
            response.fileSize = header.fileSize;
            if (sendHeader(sock, response) < 0) {
                LOGE("%s sendHeader(response) failed (%s)", statusPrefix.c_str(), strerror(errno));
                session->active.store(false);
                break;
            }

            // 通知进度
            if (m_progressCallback) {
                m_progressCallback(0, header.fileSize, statusPrefix + "Receiving: " + safeName);
            }
            break;
        }
        case CMD_DATA: {
            // 新文件开始
            if (header.currentChunk == 0 && session->recvFile.is_open()) {
                session->recvFile.close();
            }

            if (!session->recvFile.is_open()) {
                std::string filePath = m_savePath.empty() ? "./" : m_savePath;
                if (!filePath.empty() && filePath.back() != '/' && filePath.back() != '\\') {
                    filePath += "/";
                }
                // 使用时间戳后缀防止同名文件覆盖
                time_t now = time(nullptr);
                struct tm tmBuf;
                localtime_r(&now, &tmBuf);
                char ts[32];
                strftime(ts, sizeof(ts), "_%Y%m%d_%H%M%S", &tmBuf);

                std::string baseName = session->pendingFileName.empty() ? "received_file" : session->pendingFileName;
                size_t dotPos = baseName.find_last_of('.');
                if (dotPos != std::string::npos) {
                    baseName.insert(dotPos, ts);
                } else {
                    baseName += ts;
                }
                // 客户端前缀 + 时间戳后缀，避免多客户端同名互相覆盖
                filePath += clientIp + "_" + std::to_string(clientPort) + "_" + baseName;

                session->recvFile.open(filePath, std::ios::binary | std::ios::trunc);
                if (!session->recvFile.is_open()) {
                    LOGE("%s failed to open file %s", statusPrefix.c_str(), filePath.c_str());
                    break;
                }
                session->currentFilePath = filePath;   // 完成后回传给 UI
                LOGI("%s receiving: %s", statusPrefix.c_str(), filePath.c_str());
            }

            // 接收数据
            // 用"剩余字节数"算本片大小，而不是 fileSize % chunkSize：
            //   1) 空文件(0 字节)时旧算法算出 chunkSize，会去等一个永远不来的分片；
            //   2) currentChunk 越界时 % 的结果与实际偏移对不上，会写坏文件。
            if (header.chunkSize == 0 || header.currentChunk >= header.chunkCount) {
                LOGE("%s invalid chunk meta: chunk %u/%u size=%u — closing",
                    statusPrefix.c_str(), header.currentChunk, header.chunkCount, header.chunkSize);
                session->active.store(false);
                break;
            }
            uint64_t offset = (uint64_t)header.currentChunk * header.chunkSize;
            uint64_t remain = (header.fileSize > offset) ? (header.fileSize - offset) : 0;
            uint32_t dataSize = (remain > header.chunkSize) ? header.chunkSize : (uint32_t)remain;

            if (dataSize > 0) {
                std::vector<char> buffer(dataSize);
                int r = recvAll(sock, buffer.data(), dataSize, RECV_POLL_TIMEOUT_MS);
                if (r <= 0) {
                    if (r == 0) {
                        LOGI("%s client disconnected while receiving data (chunk %u/%u)",
                            statusPrefix.c_str(), header.currentChunk, header.chunkCount);
                    } else {
                        LOGE("%s recv data failed", statusPrefix.c_str());
                    }
                    if (session->recvFile.is_open()) session->recvFile.close();
                    session->active.store(false);
                    break;
                }

                session->recvFile.write(buffer.data(), r);
                session->transSize += r;
            }

            // 进度回调
            if (m_progressCallback && session->transSize > 0) {
                m_progressCallback(session->transSize, header.fileSize,
                    statusPrefix + "Receiving...");
            }
            break;
        }
        case CMD_COMPLETE: {
            LOGI("%s FileMsgSocket complete: %llu bytes",
                statusPrefix.c_str(), (unsigned long long)header.transSize);

            if (session->recvFile.is_open()) {
                session->recvFile.close();
            }

            if (m_progressCallback) {
                // 用 "|" 带上真实落盘路径：接收端文件名带时间戳后缀，
                // 上层自己按原文件名拼出来的路径是不存在的。
                std::string status = statusPrefix + "Transfer complete!";
                if (!session->currentFilePath.empty()) {
                    status += "|" + session->currentFilePath;
                }
                m_progressCallback(header.fileSize, header.fileSize, status);
            }
            session->currentFilePath.clear();
            break;
        }
        case CMD_CANCEL: {
            LOGI("%s FileMsgSocket cancelled", statusPrefix.c_str());

            if (session->recvFile.is_open()) {
                session->recvFile.close();
            }

            if (m_progressCallback) {
                m_progressCallback(0, header.fileSize, statusPrefix + "Transfer cancelled");
            }
            break;
        }
        case CMD_RESPONSE:
            // Server should never receive CMD_RESPONSE — protocol violation
            LOGW("%s unexpected CMD_RESPONSE (server does not request files)", statusPrefix.c_str());
            break;
        default:
            LOGW("%s unknown command 0x%04x", statusPrefix.c_str(), header.cmd);
            break;
        }
    }

    // 清理会话
    if (session->recvFile.is_open()) {
        session->recvFile.close();
    }
    session->active.store(false);
    close(sock);
    m_sessionMgr.removeSession(sock);
    LOGI("%s client disconnected", statusPrefix.c_str());

    if (m_progressCallback) {
        m_progressCallback(0, 0, "Client disconnected: " + clientIp + ":" + std::to_string(clientPort));
    }
}

int FileMsgSocket::sendHeader(int sock, const FileHeader& header)
{
    // 线上传输用网络序；header 本身始终保持主机序，方便日志与比较
    FileHeader net = header;
    headerToNet(net);
    return sendHeader(sock, &net, sizeof(net));
}

int FileMsgSocket::sendHeader(int sock, const void* data, size_t len)
{
    std::lock_guard<std::mutex> lock(m_sendMutex);
    if (sendAll(sock, data, len) < 0) {
        LOGE("sendAll() failed (%s)", strerror(errno));
        return -1;
    }
    return 0;
}

int FileMsgSocket::recvHeader(int sock, FileHeader& header)
{
    int ret = recvAll(sock, &header, sizeof(header), RECV_RESP_TIMEOUT_MS);
    if (ret > 0) {
        headerToHost(header);
        return 0;
    }
    return -1;
}

int FileMsgSocket::sendSliceData(int sock, const std::string& filePath, uint64_t fileSize)
{
    std::ifstream file(filePath, std::ios::binary);
    if (!file.is_open()) {
        LOGE("ifstream::open() failed: %s", filePath.c_str());
        return -1;
    }

    uint32_t chunkSize = MAX_CHUNK_SIZE;
    // 空文件也要发一个分片：chunkCount=0 时服务端一次 CMD_DATA 都收不到，
    // 也就不会创建文件（0 字节文件传完会凭空消失）。
    uint32_t chunkCount = (fileSize == 0) ? 1
                        : (uint32_t)((fileSize + chunkSize - 1) / chunkSize);
    uint64_t totalSent = 0;
    int lastPct = -1;  // 去重进度回调

    for (uint32_t i = 0; i < chunkCount && m_connected.load(); ++i) {
        // 发送数据头
        FileHeader header{};
        fillHeader(header, CMD_DATA);
        header.fileSize = fileSize;
        header.chunkSize = chunkSize;
        header.chunkCount = chunkCount;
        header.currentChunk = i;
        header.transSize = totalSent;

        if (sendHeader(sock, header) < 0) break;

        // 读取并发送数据（按剩余字节算，与服务端算法保持一致）
        uint64_t remain = fileSize - (uint64_t)i * chunkSize;
        uint32_t dataSize = (remain > chunkSize) ? chunkSize : (uint32_t)remain;

        std::streamsize bytesRead = 0;
        if (dataSize > 0) {
            std::vector<char> buffer(dataSize);
            file.read(buffer.data(), dataSize);
            bytesRead = file.gcount();

            std::lock_guard<std::mutex> lock(m_sendMutex);
            if (sendAll(sock, buffer.data(), bytesRead) < 0) {
                LOGE("sendAll() failed at chunk %u/%u", i, chunkCount);
                break;
            }
        }

        totalSent += bytesRead;

        // 进度回调（每 1% 变化时推送，去重避免刷爆消息队列）
        if (m_progressCallback) {
            int pct = fileSize > 0 ? (int)(totalSent * 100 / fileSize) : 100;
            if (pct != lastPct) {
                m_progressCallback(totalSent, fileSize, "Sending...");
                lastPct = pct;
            }
        }
    }

    file.close();

    // 发送完成消息
    FileHeader complete{};
    fillHeader(complete, CMD_COMPLETE);
    complete.fileSize = fileSize;
    complete.transSize = totalSent;
    if (sendHeader(sock, complete) < 0) {
        LOGE("sendHeader(COMPLETE) failed (%s)", strerror(errno));
    }

    if (m_progressCallback) {
        m_progressCallback(totalSent, fileSize, "Send complete!");
    }

    LOGI("FileMsgSocket sent complete: %llu bytes", (unsigned long long)totalSent);
    return 0;
}

int FileMsgSocket::postLocalFile(const std::string& filePath)
{
    if (!m_connected.load()) {
        LOGE("server not connected");
        return -1;
    }

    // ── 发送前健康检查 ──
    // m_connected 可能在服务端主动断开后仍为 true（无后台线程同步更新），
    // 发送前用 poll(POLLOUT) + getsockopt(SO_ERROR) 双重确认 socket 仍可用。
    {
        struct pollfd pfd;
        pfd.fd = m_clientSock;
        pfd.events = POLLOUT;
        pfd.revents = 0;
        int pr = poll(&pfd, 1, 0);
        // poll POLLHUP/POLLERR 检测显式断开
        if (pr > 0 && (pfd.revents & (POLLHUP | POLLERR))) {
            LOGE("postLocalFile: socket already broken (poll=%s), disconnecting",
                (pfd.revents & POLLHUP) ? "POLLHUP" : "POLLERR");
            m_connected.store(false);
            return -1;
        }
        // poll=0 可能漏检刚断开但尚未传播的连接 → SO_ERROR 兜底
        int soErr = 0;
        socklen_t soLen = sizeof(soErr);
        if (getsockopt(m_clientSock, SOL_SOCKET, SO_ERROR, &soErr, &soLen) == 0 && soErr != 0) {
            LOGE("postLocalFile: socket broken SO_ERROR=%d/%s, disconnecting",
                soErr, strerror(soErr));
            m_connected.store(false);
            return -1;
        }
    }

    struct stat st {};
    if (stat(filePath.c_str(), &st) != 0) {
        LOGE("stat(%s) failed (%s)", filePath.c_str(), strerror(errno));
        return -2;
    }

    uint64_t fileSize = st.st_size;

    // 提取文件名
    size_t pos = filePath.find_last_of("/\\");
    std::string fileName = (pos != std::string::npos) ? filePath.substr(pos + 1) : filePath;
    LOGI("sending file: [%s] size=%llu bytes", fileName.c_str(), (unsigned long long)fileSize);

    // 发送请求头
    FileHeader header{};
    fillHeader(header, CMD_REQUEST);
    header.fileNameLen = (uint32_t)fileName.size();
    header.fileSize = fileSize;
    header.chunkSize = MAX_CHUNK_SIZE;
    header.chunkCount = (uint32_t)((fileSize + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE);

    // ── 原子发送 header + filename ──
    // 两次 sendHeader 若分开调用，mutex 在中间释放，服务器可能
    // 在收到 header 后立刻读取 filename 却发现数据未到 → 协议错位。
    // 这里把 header 和 filename 合并为一次加锁的连续发送，彻底消除竞态。
    FileHeader netHeader = header;
    headerToNet(netHeader);
    {
        std::lock_guard<std::mutex> lock(m_sendMutex);
        if (sendAll(m_clientSock, &netHeader, sizeof(netHeader)) < 0) {
            LOGE("sendAll(header) failed (%s)", strerror(errno));
            return -5;
        }
        if (sendAll(m_clientSock, fileName.c_str(), fileName.size()) < 0) {
            LOGE("sendAll(filename) failed (%s)", strerror(errno));
            return -6;
        }
    }

    // 等待响应
    FileHeader response{};
    if (recvHeader(m_clientSock, response) < 0 || response.cmd != CMD_RESPONSE) {
        LOGE("recvHeader: no response from server");
        return -3;
    }

    if (m_progressCallback) {
        m_progressCallback(0, fileSize, "Transfer accepted");
    }

    // 发送文件数据
    return sendSliceData(m_clientSock, filePath, fileSize);
}

int FileMsgSocket::requestFile(const std::string& ip, unsigned short port, const std::string& fileName)
{
    int ret = connectToServer(ip, port);
    if (ret < 0) {
        return ret;
    }

    // 发送请求
    FileHeader header{};
    fillHeader(header, CMD_REQUEST);
    header.fileNameLen = (uint32_t)fileName.size();
    header.fileSize = 0;

    FileHeader netHeader = header;
    headerToNet(netHeader);
    {
        std::lock_guard<std::mutex> lock(m_sendMutex);
        if (sendAll(m_clientSock, &netHeader, sizeof(netHeader)) < 0) {
            LOGE("sendAll(header) failed (%s)", strerror(errno));
            return -1;
        }
        if (sendAll(m_clientSock, fileName.c_str(), fileName.size()) < 0) {
            LOGE("sendAll(filename) failed (%s)", strerror(errno));
            return -2;
        }
    }

    return 0;
}
