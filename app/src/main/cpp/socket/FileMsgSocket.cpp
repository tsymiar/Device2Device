//
// Created by Shenyrion on 2025.
//
// 协议与状态机对齐 MyAutomatic/LinxSrvc/Mac/Transfer 的 TransferEngine：
//  - 64 字节协议头（event 复用 reserved[0]）、统一大端（网络字节序）传输
//  - 文件名做长度校验 + 路径净化（防 DoS / 路径穿越）
//  - 分片大小按「剩余字节」计算（修正空文件与末片越界写坏文件的问题）
//  - 客户端任务用 future 托管并在 accept 时回收（长跑不再堆积线程对象）
//  - CMD_CANCEL / CMD_DELETE：分片边界取消 + 删除对端半截文件
//  - CMD_REQUEST 带 event=PULL 时服务端代发文件（拉取模式）
//  - 进度回调带事件码，日志按 1% 步进输出 MB/s
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
#include <cstdio>
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
    memcpy(h.magic, kProtocolMagic, sizeof(kProtocolMagic));
    h.version = PROTOCOL_VERSION;
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
// 返回值: >0 成功（应等于len），0 客户端断开(EOF/POLLHUP)，-1 超时（可重试），-2 其他错误，
//         -3 runFlag 已被清false（主动停止）。注意：runFlag 是「运行标志」，true = 继续跑。
static int recvAll(int sock, void* buf, size_t len, int timeoutMs,
                   const std::atomic<bool>* runFlag = nullptr)
{
    struct pollfd pfd {};
    pfd.fd = sock;
    pfd.events = POLLIN;

    size_t total = 0;
    while (total < len) {
        // 运行标志被清 false：不必等满一次 poll 超时，立刻让调用方退出。
        // -3 = 主动停止，区别于网络错误（-2）/ 空闲超时（-1）。
        if (runFlag != nullptr && !runFlag->load()) return -3;
        int pr = poll(&pfd, 1, timeoutMs);
        if (pr == 0) {
            return -1;  // 超时，无数据
        }
        if (pr < 0) {
            if (errno == EINTR) continue;   // 信号中断，重试
            return -2;  // poll 错误
        }
        // fd 已被 stopServer 关闭时 poll 返回 POLLNVAL。旧实现既不处理也不退出，
        // 会在这里无限空转，导致 stopServer 的 f.wait() 永远等不到 clientHandler 结束。
        if (pfd.revents & POLLNVAL) return -2;
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
        if (!pair.second) continue;
        // 先置失效：clientHandler / sendSliceData 拿 active 当运行标志，
        // 否则正在传大文件的线程要等整份文件发完，stopServer 才能收尾。
        pair.second->active.store(false);
        if (pair.second->sock >= 0) {
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
    , m_backoffEnabled(false)
    , m_cancelRequested(false)
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
    // accept() 也要有上限：shutdown() 未必能唤醒阻塞中的 accept。超时后
    // fileServerProcess 会 continue，停止时则退出，保证 join() 不会挂住。
    struct timeval acceptTv;
    acceptTv.tv_sec = 1;
    acceptTv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &acceptTv, sizeof(acceptTv));

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
    // 这里不打 "connecting to ..."：唯一的调用方 connectToServer() 上来就打过了，
    // 一条连接刷两行一样的话
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
        // 且返回的 hostent 指向静态内存，多线程同时解析会互相干扰。
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
        int sockErr = 0;
        socklen_t soLen = sizeof(sockErr);
        if (getsockopt(sock, SOL_SOCKET, SO_ERROR, &sockErr, &soLen) < 0 || sockErr != 0) {
            LOGE("connect() failed: SO_ERROR=%d/%s", sockErr, sockErr ? strerror(sockErr) : "getsockopt error");
            close(sock);
            return -3;
        }
    }

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

void FileMsgSocket::interrupt()
{
    m_serverRunning.store(false);
    m_running.store(false);
    m_connected.store(false);

    // 关闭所有客户端 socket，立即中断其阻塞的 recv() 调用，并让会话失效
    m_sessionMgr.closeAllSockets();

    if (m_serverSock >= 0) {
        // shutdown 唤醒阻塞的 accept()，close 立刻释放端口，
        // 这样「Stop Server 后马上用同一端口重启」才能 bind 成功。
        shutdown(m_serverSock, SHUT_RDWR);
        close(m_serverSock);
        m_serverSock = -1;
    }

    // 客户端 fd 不在这里碰：m_clientSock 由 disconnect() 在 m_sendMutex 下读写，
    // 在 UI 线程直接 shutdown/close 会和它数据竞争。m_connected=false 已让
    // postLocalFile / receivePullData 在分片边界停下，fd 交给后台析构持锁关闭。
}

void FileMsgSocket::stopServer()
{
    // 幂等：interrupt() 可能已在 UI 线程提前执行过
    interrupt();

    if (m_serverThread.joinable()) {
        m_serverThread.join();
    }

    // 必须在本对象析构前等所有 clientHandler 退出，否则它们会访问已析构的 this。
    // 这一步可能耗时，调用方绝不能是 UI 线程（见 JniMethods.cpp 的 releaseFileMsg）。
    {
        std::lock_guard<std::mutex> lock(m_clientThreadMutex);
        for (std::future<void>& f : m_clientFutures) {
            if (!f.valid()) continue;
            if (f.wait_for(std::chrono::seconds(2)) != std::future_status::ready) {
                LOGW("stopServer: client task still running after 2s, keep waiting");
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

    // 持发送锁关 fd：等当前 sendAll 结束，防止 fd 被复用后把上一轮的数据发错连接；
    // 先 shutdown 唤醒阻塞在 recv 上的调用方。
    {
        std::lock_guard<std::mutex> lock(m_sendMutex);
        if (m_clientSock >= 0) {
            LOGI("disconnect: closing connection to %s:%d (fd=%d)",
                m_serverIp.c_str(), m_serverPort, (int)m_clientSock);
            shutdown(m_clientSock, SHUT_RDWR);
            close(m_clientSock);
            m_clientSock = -1;
        }
    }

    if (m_receiveThread.joinable()) {
        m_receiveThread.join();
    }

    LOGI("FileMsgSocket disconnected");
}

void FileMsgSocket::cancelTransfer()
{
    m_cancelRequested.store(true);
    LOGI("cancel requested — takes effect at next chunk boundary");
}

void FileMsgSocket::setBackoffEnabled(bool enabled)
{
    m_backoffEnabled.store(enabled);
    LOGI("ENOTCONN backoff %s", enabled ? "enabled" : "disabled");
}

int FileMsgSocket::deletePartialFile(const std::string& fileName)
{
    if (fileName.empty()) {
        LOGE("deletePartialFile: empty fileName");
        return -1;
    }
    if (!m_connected.load()) {
        LOGE("deletePartialFile: not connected");
        return -2;
    }

    // header + filename 必须持锁按序发出（与服务端解析约定一致）
    FileHeader header{};
    fillHeader(header, CMD_DELETE);
    header.fileNameLen = (uint32_t)fileName.size();

    FileHeader netHeader = header;
    headerToNet(netHeader);
    {
        std::lock_guard<std::mutex> lock(m_sendMutex);
        if (sendAll(m_clientSock, &netHeader, sizeof(netHeader)) < 0 ||
            sendAll(m_clientSock, fileName.c_str(), fileName.size()) < 0) {
            LOGE("sendAll(delete request) failed (%s)", strerror(errno));
            return -3;
        }
    }
    LOGI("deletePartialFile: requested deletion of \"%s\" on peer", fileName.c_str());
    return 0;
}

void FileMsgSocket::fileServerProcess()
{
    struct sockaddr_in clientAddr {};
    socklen_t addrLen = sizeof(clientAddr);

    while (m_serverRunning.load()) {
        addrLen = sizeof(clientAddr);
        int clientSock = accept(m_serverSock, (struct sockaddr*)&clientAddr, &addrLen);
        if (clientSock < 0) {
            if (!m_serverRunning.load()) break;   // 停止中，正常退出
            // 监听 socket 设了 1s SO_RCVTIMEO：空闲超时不是错误，继续监听
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) continue;
            LOGE("accept() failed (%s)", strerror(errno));
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
        // 接收超时兜底：recvAll 用 poll，但 poll 报可读后的 recv 仍可能阻塞；
        // 停止时 fd 已被 shutdown/close，有了上限后台任务就能及时收尾。
        struct timeval rcvTv;
        rcvTv.tv_sec  = 2;
        rcvTv.tv_usec = 0;
        setsockopt(clientSock, SOL_SOCKET, SO_RCVTIMEO, &rcvTv, sizeof(rcvTv));

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
    // 客户端模式下不需要后台接收：响应和超时都由 postLocalFile 所在的调用线程同步处理。
    // 此函数保留以兼容头文件声明，connectToServer 不启动它。
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

    m_sessionMgr.addSession(sock, clientIp, clientPort);
    ClientSession* session = m_sessionMgr.getSession(sock);
    if (!session) {
        close(sock);
        return;
    }

    if (m_progressCallback) {
        m_progressCallback(0, 0, "Client connected from " + clientIp + ":" + std::to_string(clientPort),
            FILE_EVT_CONNECTED);
    }

    std::string statusPrefix = "[" + clientIp + ":" + std::to_string(clientPort) + "] ";

    // ── ENOTCONN / EBADF：对端已 abort/RST，连接已死且重试不会自愈 ──
    // 默认短宽限后判死；指数退避会长时间占住线程，仅作为可选项（与 Mac/Transfer 一致）。
    static constexpr int ENOTCONN_RETRY_MAX = 3;
    static constexpr int ENOTCONN_RETRY_DELAY_US = 20000;  // 固定 20ms 宽限
    static constexpr int BACKOFF_RETRY_MAX = 16;
    static constexpr int BACKOFF_BASE_DELAY_US = 10000;    // 起始 10ms，逐次翻倍
    int errRetries = 0;
    int lastLoggedPct = -1;   // 进度日志按 1% 步进去重
    auto lastSpeedTime = std::chrono::steady_clock::now();
    uint64_t lastSpeedBytes = 0;

    while (m_running.load() && session->active.load()) {
        FileHeader header{};
        int ret = recvAll(sock, &header, sizeof(header), RECV_POLL_TIMEOUT_MS, &m_running);
        if (ret <= 0) {
            if (ret == -3) {
                LOGI("%s stopping (server stopped)", statusPrefix.c_str());
                break;
            }
            if (ret == -1) {
                // 空闲连接每 5s 到此一次，回到 while 复查 m_running
                errRetries = 0;
                continue;
            }
            if (ret == -2) {
                const bool backoff = m_backoffEnabled.load();
                const int retryMax = backoff ? BACKOFF_RETRY_MAX : ENOTCONN_RETRY_MAX;
                if (errRetries < retryMax) {
                    ++errRetries;
                    long delayUs = backoff
                        ? (long)(BACKOFF_BASE_DELAY_US * (1LL << (errRetries - 1)))
                        : (long)ENOTCONN_RETRY_DELAY_US;
                    LOGW("%s recv header ENOTCONN/EBADF, retry %d/%d after %ldus (backoff=%d)",
                        statusPrefix.c_str(), errRetries, retryMax, delayUs, (int)backoff);
                    // tv_nsec 必须 < 1e9，退避后期要把整秒拆到 tv_sec
                    timespec nap;
                    nap.tv_sec = (time_t)(delayUs / 1000000);
                    nap.tv_nsec = (long)((delayUs % 1000000) * 1000);
                    nanosleep(&nap, nullptr);
                    continue;
                }
                LOGW("%s recv header ENOTCONN persists after %d retries — peer aborted, closing (fd=%d)",
                    statusPrefix.c_str(), retryMax, sock);
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
        if (memcmp(header.magic, kProtocolMagic, sizeof(kProtocolMagic)) != 0) {
            LOGE("%s invalid magic number: 0x%08x, expect 0x%08x, client disconnected", statusPrefix.c_str(), *(uint32_t*)header.magic, *(uint32_t*)kProtocolMagic);
            break;
        }

        // CMD_DATA 逐片打日志会刷屏，进度在分支内按 1% 步进打印
        if (header.cmd != CMD_DATA) {
            LOGI("%s Received command: %d", statusPrefix.c_str(), header.cmd);
        }
        switch (header.cmd) {
        case CMD_REQUEST: {
            // 文件名长度必须校验：客户端可以伪造一个 4GB 的 fileNameLen，
            // 直接以该长度构造 std::string 将导致内存耗尽（DoS）。
            if (header.fileNameLen == 0 || header.fileNameLen > MAX_FILE_NAME_LEN) {
                LOGE("%s invalid fileNameLen=%u (max %u) — closing",
                    statusPrefix.c_str(), header.fileNameLen, MAX_FILE_NAME_LEN);
                session->active.store(false);
                break;
            }

            std::string fileName(header.fileNameLen, '\0');
            int r = recvAll(sock, &fileName[0], header.fileNameLen, RECV_POLL_TIMEOUT_MS, &m_running);
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

            // ── 拉取模式：按 event 码区分 push/pull（二者 fileSize 都可能为 0）──
            if (header.event == FILE_EVT_REQUEST_PULL) {
                std::string dir = m_savePath.empty() ? "./" : m_savePath;
                if (!dir.empty() && dir.back() != '/' && dir.back() != '\\') {
                    dir += "/";
                }
                std::string filePath = dir + safeName;

                FileHeader resp{};
                fillHeader(resp, CMD_RESPONSE);
                struct stat st {};
                if (stat(filePath.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) {
                    LOGW("%s pull request rejected: %s not found (fd=%d)",
                        statusPrefix.c_str(), filePath.c_str(), sock);
                    resp.fileSize = 0;   // 约定 fileSize=0 为拒绝
                    if (sendHeader(sock, resp) < 0) {
                        LOGE("%s sendHeader(pull reject) failed — closing", statusPrefix.c_str());
                        session->active.store(false);
                    }
                    break;
                }

                resp.fileSize = (uint64_t)st.st_size;
                if (sendHeader(sock, resp) < 0) {
                    LOGE("%s sendHeader(pull accept) failed — closing", statusPrefix.c_str());
                    session->active.store(false);
                    break;
                }

                LOGI("%s serving pull request: %s (%llu bytes)", statusPrefix.c_str(),
                    filePath.c_str(), (unsigned long long)st.st_size);
                // 服务端代发：runFlag 传 session->active 而非 m_connected
                sendSliceData(sock, filePath, (uint64_t)st.st_size, session->active);
                break;
            }

            // ── push 模式：对端推文件过来 ──
            session->pendingFileName = safeName;
            session->pendingFileSize = header.fileSize;
            session->transSize = 0;
            lastLoggedPct = -1;   // 新文件从 0% 重新计步
            lastSpeedTime = std::chrono::steady_clock::now();
            lastSpeedBytes = 0;

            FileHeader response{};
            fillHeader(response, CMD_RESPONSE);
            response.fileSize = header.fileSize;
            if (sendHeader(sock, response) < 0) {
                LOGE("%s sendHeader(response) failed (%s)", statusPrefix.c_str(), strerror(errno));
                session->active.store(false);
                break;
            }

            if (m_progressCallback) {
                m_progressCallback(0, header.fileSize, statusPrefix + "Receiving: " + safeName,
                    FILE_EVT_RECEIVE_START);
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

            // offset 与已收字节数不符 = 协议错位，继续写会写坏文件，判死
            if (offset != session->transSize) {
                LOGE("%s chunk out of order: chunk %u offset=%llu but received %llu — closing",
                    statusPrefix.c_str(), header.currentChunk, (unsigned long long)offset,
                    (unsigned long long)session->transSize);
                if (session->recvFile.is_open()) session->recvFile.close();
                session->active.store(false);
                break;
            }

            if (dataSize > 0) {
                if (session->chunkBuf.size() < dataSize) {
                    session->chunkBuf.resize(dataSize);
                }
                int r = recvAll(sock, session->chunkBuf.data(), dataSize, RECV_POLL_TIMEOUT_MS, &m_running);
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

                session->recvFile.write(session->chunkBuf.data(), r);
                if (!session->recvFile.good()) {
                    // 磁盘满/IO 错误时不中止会把损坏文件当成功传完
                    LOGE("%s file write failed (disk full?) — closing", statusPrefix.c_str());
                    session->recvFile.close();
                    session->active.store(false);
                    break;
                }
                session->transSize += r;

                // 每增加 1% 打一次日志，避免逐分片刷屏
                if (header.fileSize > 0) {
                    int pct = (int)(session->transSize * 100 / header.fileSize);
                    if (pct != lastLoggedPct) {
                        auto now = std::chrono::steady_clock::now();
                        double sec = std::chrono::duration<double>(now - lastSpeedTime).count();
                        double mbps = sec > 0.001
                            ? (double)(session->transSize - lastSpeedBytes) / sec / (1024.0 * 1024.0)
                            : 0.0;
                        LOGI("%s receiving: %d%% (%llu/%llu bytes, %.1f MB/s)",
                            statusPrefix.c_str(), pct,
                            (unsigned long long)session->transSize,
                            (unsigned long long)header.fileSize, mbps);
                        lastLoggedPct = pct;
                        lastSpeedTime = now;
                        lastSpeedBytes = session->transSize;
                    }
                }
            }

            if (m_progressCallback && session->transSize > 0) {
                m_progressCallback(session->transSize, header.fileSize,
                    statusPrefix + "Receiving...", FILE_EVT_NONE);
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
                m_progressCallback(header.fileSize, header.fileSize, status,
                    FILE_EVT_TRANSFER_COMPLETE);
            }
            session->currentFilePath.clear();
            break;
        }
        case CMD_CANCEL: {
            LOGI("%s FileMsgSocket cancelled", statusPrefix.c_str());

            if (session->recvFile.is_open()) {
                session->recvFile.close();
            }

            // 记录半截文件，等待对端 CMD_DELETE 精确删除
            struct stat st {};
            if (!session->currentFilePath.empty() &&
                stat(session->currentFilePath.c_str(), &st) == 0 && S_ISREG(st.st_mode)) {
                std::lock_guard<std::mutex> lock(m_partialMutex);
                m_cancelledPartials[session->pendingFileName] = session->currentFilePath;
            }

            if (m_progressCallback) {
                m_progressCallback(0, header.fileSize, statusPrefix + "Transfer cancelled",
                    FILE_EVT_TRANSFER_CANCELLED);
            }
            break;
        }
        case CMD_DELETE: {
            // 与 CMD_REQUEST 相同的文件名校验与读取
            if (header.fileNameLen == 0 || header.fileNameLen > MAX_FILE_NAME_LEN) {
                LOGE("%s invalid fileNameLen=%u (max %u) — closing",
                    statusPrefix.c_str(), header.fileNameLen, MAX_FILE_NAME_LEN);
                session->active.store(false);
                break;
            }
            std::string fileName(header.fileNameLen, '\0');
            int r = recvAll(sock, &fileName[0], header.fileNameLen, RECV_POLL_TIMEOUT_MS, &m_running);
            if (r <= 0) {
                LOGE("%s recv filename timeout — protocol desync, closing", statusPrefix.c_str());
                session->active.store(false);
                break;
            }
            std::string safeName = sanitizeFileName(fileName);

            // 只删 CMD_CANCEL 时记录过的路径，原始文件名找不到记录则忽略
            std::string partial;
            {
                std::lock_guard<std::mutex> lock(m_partialMutex);
                auto it = m_cancelledPartials.find(safeName);
                if (it != m_cancelledPartials.end()) {
                    partial = it->second;
                    m_cancelledPartials.erase(it);
                }
            }
            struct stat st {};
            if (partial.empty() || stat(partial.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) {
                LOGW("%s delete request: no partial file recorded for \"%s\"",
                    statusPrefix.c_str(), safeName.c_str());
                if (m_progressCallback) {
                    m_progressCallback(0, 0, statusPrefix + "Partial not found: " + safeName,
                        FILE_EVT_NONE);
                }
                break;
            }
            if (remove(partial.c_str()) != 0) {
                LOGE("%s remove(%s) failed: %s", statusPrefix.c_str(), partial.c_str(), strerror(errno));
                if (m_progressCallback) {
                    m_progressCallback(0, 0, statusPrefix + "Partial delete failed: " + safeName,
                        FILE_EVT_NONE);
                }
                break;
            }
            LOGI("%s deleted partial file: %s (%llu bytes)", statusPrefix.c_str(),
                partial.c_str(), (unsigned long long)st.st_size);
            if (m_progressCallback) {
                m_progressCallback(0, 0, statusPrefix + "Partial deleted: " + safeName,
                    FILE_EVT_NONE);
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

    if (session->recvFile.is_open()) {
        session->recvFile.close();
    }
    session->active.store(false);
    close(sock);
    m_sessionMgr.removeSession(sock);
    LOGI("%s client disconnected", statusPrefix.c_str());

    if (m_progressCallback) {
        m_progressCallback(0, 0, "Client disconnected: " + clientIp + ":" + std::to_string(clientPort),
            FILE_EVT_DISCONNECTED);
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

int FileMsgSocket::sendSliceData(int sock, const std::string& filePath, uint64_t fileSize,
                                const std::atomic<bool>& runFlag)
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
    auto lastSpeedTime = std::chrono::steady_clock::now();
    uint64_t lastSpeedBytes = 0;
    bool cancelled = false;
    int ret = 0;

    for (uint32_t i = 0; i < chunkCount && runFlag.load(); ++i) {
        // 取消在分片边界生效：向对端发 CMD_CANCEL，由其停止接收并记录半截文件
        if (m_cancelRequested.exchange(false)) {
            cancelled = true;
            break;
        }

        FileHeader header{};
        fillHeader(header, CMD_DATA);
        header.fileSize = fileSize;
        header.chunkSize = chunkSize;
        header.chunkCount = chunkCount;
        header.currentChunk = i;
        header.transSize = totalSent;

        if (sendHeader(sock, header) < 0) { ret = -3; break; }

        // 读取并发送数据（按剩余字节算，与服务端算法保持一致）
        std::streamsize bytesRead = 0;
        if (fileSize > 0) {
            uint64_t remain = fileSize - (uint64_t)i * chunkSize;
            uint32_t dataSize = (remain > chunkSize) ? chunkSize : (uint32_t)remain;

            std::vector<char> buffer(dataSize);
            file.read(buffer.data(), dataSize);
            bytesRead = file.gcount();

            std::lock_guard<std::mutex> lock(m_sendMutex);
            if (sendAll(sock, buffer.data(), bytesRead) < 0) {
                LOGE("sendAll() failed at chunk %u/%u", i, chunkCount);
                ret = -3;
                break;
            }
        }

        totalSent += (uint64_t)bytesRead;

        // 进度回调（每 1% 变化时推送，去重避免刷爆消息队列）+ 速度日志
        if (m_progressCallback) {
            int pct = fileSize > 0 ? (int)(totalSent * 100 / fileSize) : 100;
            if (pct != lastPct) {
                auto now = std::chrono::steady_clock::now();
                double sec = std::chrono::duration<double>(now - lastSpeedTime).count();
                double mbps = sec > 0.001
                    ? (double)(totalSent - lastSpeedBytes) / sec / (1024.0 * 1024.0) : 0.0;
                LOGI("sendSliceData: %d%% (%llu/%llu bytes, %.1f MB/s)", pct,
                    (unsigned long long)totalSent, (unsigned long long)fileSize, mbps);
                m_progressCallback(totalSent, fileSize, "Sending...", FILE_EVT_NONE);
                lastPct = pct;
                lastSpeedTime = now;
                lastSpeedBytes = totalSent;
            }
        }
    }

    file.close();

    if (cancelled) {
        FileHeader cancel{};
        fillHeader(cancel, CMD_CANCEL);
        cancel.fileSize = fileSize;
        cancel.transSize = totalSent;
        cancel.event = FILE_EVT_TRANSFER_CANCELLED;
        sendHeader(sock, cancel);  // 尽力而为：对端可能已不在线
        if (m_progressCallback) {
            m_progressCallback(totalSent, fileSize, "Send cancelled", FILE_EVT_TRANSFER_CANCELLED);
        }
        LOGI("sendSliceData: cancelled at %llu/%llu bytes",
            (unsigned long long)totalSent, (unsigned long long)fileSize);
        return -2;
    }
    if (ret < 0) return ret;  // 发送失败：不发 COMPLETE，避免对端把半截文件当成功

    FileHeader complete{};
    fillHeader(complete, CMD_COMPLETE);
    complete.fileSize = fileSize;
    complete.transSize = totalSent;
    complete.event = FILE_EVT_TRANSFER_COMPLETE;
    if (sendHeader(sock, complete) < 0) {
        LOGE("sendHeader(COMPLETE) failed (%s)", strerror(errno));
    }

    if (m_progressCallback) {
        m_progressCallback(totalSent, fileSize, "Send complete!", FILE_EVT_TRANSFER_COMPLETE);
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
    m_cancelRequested.store(false);  // 清掉上一次可能残留的取消请求

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

    size_t pos = filePath.find_last_of("/\\");
    std::string fileName = (pos != std::string::npos) ? filePath.substr(pos + 1) : filePath;
    LOGI("sending file: [%s] size=%llu bytes", fileName.c_str(), (unsigned long long)fileSize);

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

    FileHeader response{};
    if (recvHeader(m_clientSock, response) < 0 || response.cmd != CMD_RESPONSE) {
        LOGE("recvHeader: no response from server");
        return -3;
    }

    if (m_progressCallback) {
        // SEND_START 不受 JNI 节流：NONE 进度在快传时会被吞光，进度条没机会出现
        m_progressCallback(0, fileSize, "Transfer accepted", FILE_EVT_SEND_START);
    }

    return sendSliceData(m_clientSock, filePath, fileSize, m_connected);
}

int FileMsgSocket::requestFile(const std::string& ip, unsigned short port, const std::string& fileName)
{
    if (fileName.empty()) {
        LOGE("requestFile: empty fileName");
        return -1;
    }
    m_cancelRequested.store(false);  // 清掉上一次可能残留的取消请求

    int ret = connectToServer(ip, port);
    if (ret < 0) {
        return ret;
    }

    // 拉取请求：event=REQUEST_PULL 标识模式，fileSize=0 表示本端不带数据
    FileHeader header{};
    fillHeader(header, CMD_REQUEST);
    header.event = FILE_EVT_REQUEST_PULL;
    header.fileNameLen = (uint32_t)fileName.size();

    FileHeader netHeader = header;
    headerToNet(netHeader);
    {
        std::lock_guard<std::mutex> lock(m_sendMutex);
        if (sendAll(m_clientSock, &netHeader, sizeof(netHeader)) < 0) {
            LOGE("sendAll(header) failed (%s)", strerror(errno));
            return -2;
        }
        if (sendAll(m_clientSock, fileName.c_str(), fileName.size()) < 0) {
            LOGE("sendAll(filename) failed (%s)", strerror(errno));
            return -3;
        }
    }

    FileHeader response{};
    if (recvHeader(m_clientSock, response) < 0 || response.cmd != CMD_RESPONSE) {
        LOGE("recvHeader: no response from server");
        return -4;
    }
    if (response.fileSize == 0) {
        LOGE("pull request rejected by server: %s (not found or not a regular file)",
            fileName.c_str());
        return -5;
    }

    if (m_progressCallback) {
        m_progressCallback(0, response.fileSize, "Pulling: " + fileName, FILE_EVT_RECEIVE_START);
    }

    return receivePullData(m_clientSock, fileName, response.fileSize);
}

// 拉取接收循环：分片语义与服务端 CMD_DATA 一致（顺序校验 / 写盘检查 / 1% 日志）
int FileMsgSocket::receivePullData(int sock, const std::string& fileName, uint64_t fileSize)
{
    std::string dir = m_savePath.empty() ? "./" : m_savePath;
    if (!dir.empty() && dir.back() != '/' && dir.back() != '\\') {
        dir += '/';
    }
    std::string baseName = sanitizeFileName(fileName);
    time_t now = time(nullptr);
    struct tm tmBuf;
    localtime_r(&now, &tmBuf);
    char ts[32];
    strftime(ts, sizeof(ts), "_%Y%m%d_%H%M%S", &tmBuf);
    size_t dotPos = baseName.find_last_of('.');
    if (dotPos != std::string::npos) {
        baseName.insert(dotPos, ts);
    } else {
        baseName += ts;
    }
    std::string filePath = dir + baseName;

    std::ofstream out(filePath, std::ios::binary | std::ios::trunc);
    if (!out.is_open()) {
        LOGE("receivePullData: failed to open file %s", filePath.c_str());
        return -1;
    }
    LOGI("receivePullData: saving to %s", filePath.c_str());

    uint64_t received = 0;
    int lastPct = -1;
    auto lastSpeedTime = std::chrono::steady_clock::now();
    uint64_t lastSpeedBytes = 0;
    std::vector<char> buf;
    bool completed = false;

    while (m_connected.load()) {
        // 本端取消拉取：停止落盘，连接保持，由上层决定是否断开
        if (m_cancelRequested.exchange(false)) {
            out.close();
            if (m_progressCallback) {
                m_progressCallback(received, fileSize, "Pull cancelled",
                    FILE_EVT_TRANSFER_CANCELLED);
            }
            LOGI("receivePullData: cancelled at %llu/%llu bytes",
                (unsigned long long)received, (unsigned long long)fileSize);
            return -8;
        }

        FileHeader header{};
        int ret = recvAll(sock, &header, sizeof(header), RECV_POLL_TIMEOUT_MS);
        if (ret <= 0) {
            LOGW("receivePullData: recv header failed (ret=%d) — aborting", ret);
            out.close();
            return -2;
        }
        headerToHost(header);

        if (header.cmd == CMD_COMPLETE) {
            completed = true;
            break;
        }
        if (header.cmd == CMD_CANCEL) {
            out.close();
            if (m_progressCallback) {
                m_progressCallback(received, fileSize, "Pull cancelled by peer",
                    FILE_EVT_TRANSFER_CANCELLED);
            }
            LOGI("receivePullData: cancelled by peer at %llu/%llu bytes",
                (unsigned long long)received, (unsigned long long)fileSize);
            return -8;
        }
        if (header.cmd != CMD_DATA) {
            LOGE("receivePullData: unexpected cmd %u — aborting", header.cmd);
            out.close();
            return -3;
        }
        if (header.chunkSize == 0 || header.currentChunk >= header.chunkCount) {
            LOGE("receivePullData: invalid chunk meta: chunk %u/%u size=%u",
                header.currentChunk, header.chunkCount, header.chunkSize);
            out.close();
            return -4;
        }

        uint64_t offset = (uint64_t)header.currentChunk * header.chunkSize;
        if (offset != received) {
            LOGE("receivePullData: chunk out of order: chunk %u offset=%llu but received %llu",
                header.currentChunk, (unsigned long long)offset, (unsigned long long)received);
            out.close();
            return -5;
        }

        uint64_t remain = (fileSize > offset) ? (fileSize - offset) : 0;
        uint32_t dataSize = (remain > header.chunkSize) ? header.chunkSize : (uint32_t)remain;
        if (dataSize == 0) {
            continue;
        }

        if (buf.size() < dataSize) {
            buf.resize(dataSize);
        }
        int r = recvAll(sock, buf.data(), dataSize, RECV_POLL_TIMEOUT_MS);
        if (r <= 0) {
            LOGE("receivePullData: recv data failed (ret=%d)", r);
            out.close();
            return -6;
        }
        out.write(buf.data(), r);
        if (!out.good()) {
            LOGE("receivePullData: file write failed (disk full?) — aborting");
            out.close();
            return -7;
        }
        received += r;

        if (m_progressCallback) {
            m_progressCallback(received, fileSize, "Pulling: " + fileName, FILE_EVT_NONE);
        }
        int pct = fileSize > 0 ? (int)(received * 100 / fileSize) : 100;
        if (pct != lastPct) {
            auto now2 = std::chrono::steady_clock::now();
            double sec = std::chrono::duration<double>(now2 - lastSpeedTime).count();
            double mbps = sec > 0.001
                ? (double)(received - lastSpeedBytes) / sec / (1024.0 * 1024.0) : 0.0;
            LOGI("receivePullData: %d%% (%llu/%llu bytes, %.1f MB/s)", pct,
                (unsigned long long)received, (unsigned long long)fileSize, mbps);
            lastPct = pct;
            lastSpeedTime = now2;
            lastSpeedBytes = received;
        }
    }

    out.close();

    // 连接中途断开不能当成"传完"：否则半截文件会被搬到公共下载目录
    if (!completed) {
        LOGE("receivePullData: connection closed before CMD_COMPLETE — file incomplete");
        if (m_progressCallback) {
            m_progressCallback(received, fileSize, "Pull failed: connection lost", FILE_EVT_NONE);
        }
        return -2;
    }

    if (m_progressCallback) {
        // "|" 后带真实落盘路径，上层据此搬到公共下载目录
        m_progressCallback(fileSize, fileSize, "Pull complete!|" + filePath,
            FILE_EVT_TRANSFER_COMPLETE);
    }
    LOGI("receivePullData: complete: %llu bytes -> %s",
        (unsigned long long)received, filePath.c_str());
    return 0;
}
