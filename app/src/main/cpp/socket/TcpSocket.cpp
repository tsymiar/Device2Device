//
// Created by Shenyrion on 2022/5/2.
//

#include "TcpSocket.h"
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <string>
#include <thread>
#include <mutex>

#ifndef LOG_TAG
#define LOG_TAG "TcpSocket"
#endif

#include <utils/logging.h>
#include <cerrno>

int TcpSocket::Receiver(SOCKETHOOK callback) const
{
    int sock = this->GetSocket();
    size_t size = this->GetSize();
    uint8_t data[size];
    ssize_t res = ::recv(sock, data, size, 0);
    if (0 == res || (res < 0 && errno != EAGAIN)) {
        close(sock);
        return -2;
    }
    if (callback != nullptr) {
        return callback(data, size);
    }
    return -3;
}

int TcpSocket::Start(unsigned short port)
{
    m_listenSock = ::socket(AF_INET, SOCK_STREAM, 0);
    if (m_listenSock < 0) {
        LOGE("Generating socket (%s).",
             (errno != 0 ? strerror(errno) : std::to_string(m_listenSock).c_str()));
        return -1;
    }

    // 设置 SO_REUSEADDR，确保 stop 后端口能立即复用
    int opt = 1;
    setsockopt(m_listenSock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in local{};
    local.sin_port = htons(port);
    local.sin_family = AF_INET;
    local.sin_addr.s_addr = INADDR_ANY;
    if (::bind(m_listenSock, reinterpret_cast<struct sockaddr *>(&local), sizeof(local)) < 0) {
        LOGE("Binding socket address (%s).",
             (errno != 0 ? strerror(errno) : std::to_string(m_listenSock).c_str()));
        close(m_listenSock);
        m_listenSock = -1;
        return -2;
    }

    const int backlog = 50;
    if (listen(m_listenSock, backlog) < 0) {
        LOGE("Socket listen (%s).",
             (errno != 0 ? strerror(errno) : std::to_string(m_listenSock).c_str()));
        close(m_listenSock);
        m_listenSock = -1;
        return -3;
    }

    struct sockaddr_in lstnaddr{};
    auto listenLen = static_cast<socklen_t>(sizeof(lstnaddr));
    getsockname(m_listenSock, reinterpret_cast<struct sockaddr *>(&lstnaddr), &listenLen);
    LOGI("localhost listening [%s:%d].", inet_ntoa(lstnaddr.sin_addr), port);
    m_running.store(true);

    while (m_running.load()) {
        struct sockaddr_in sin{};
        auto len = static_cast<socklen_t>(sizeof(sin));
        std::lock_guard<std::mutex> lock(m_acceptMutex);
        int rcv_sock = m_recvSock = ::accept(m_listenSock, reinterpret_cast<struct sockaddr *>(&sin), &len);
        if (rcv_sock < 0) {
            // Finish() 关闭了 m_listenSock 时会触发此分支，属于正常停止流程
            if (!m_running.load()) break;
            LOGE("Socket accept (%s).",
                 (errno != 0 ? strerror(errno) : std::to_string(rcv_sock).c_str()));
            return -4;
        }
        {
            std::mutex mtxLck{};
            std::lock_guard <std::mutex> lock(mtxLck);
            time_t t{};
            time(&t);
            struct tm *lt = localtime(&t);
            char ipaddr[INET_ADDRSTRLEN];
            struct sockaddr_in peeraddr{};
            auto peerLen = static_cast<socklen_t>(sizeof(peeraddr));
            bool set = true;
            setsockopt(rcv_sock, SOL_SOCKET, SO_KEEPALIVE, reinterpret_cast<const char *>(&set),
                       sizeof(bool));
            getpeername(rcv_sock, reinterpret_cast<struct sockaddr *>(&peeraddr), &peerLen);
            const char *IP = inet_ntop(AF_INET, &peeraddr.sin_addr, ipaddr, sizeof(ipaddr));
            unsigned short PORT = ntohs(peeraddr.sin_port);
            LOGI("accepted peer(%d) address [%s:%d] (@ %d/%02d/%02d-%02d:%02d:%02d); waitting massage...",
                 rcv_sock, IP, PORT,
                 lt->tm_year + 1900, lt->tm_mon + 1, lt->tm_mday, lt->tm_hour, lt->tm_min,
                 lt->tm_sec);
        }
        if (m_callback != nullptr) {
            std::thread th(
                    [=](TcpSocket *clazz) -> int {
                        int ret = 0;
                        while (m_running) {
                            ret = Receiver(m_callback);
                        }
                        return ret;
                    }, this);
            if (th.joinable()) {
                th.detach();
            }
        }
        usleep(100);
    }

    // 正常退出时关闭监听 socket
    if (m_listenSock >= 0) {
        shutdown(m_listenSock, SHUT_RDWR);
        close(m_listenSock);
        m_listenSock = -1;
    }
    return 0;
}

void TcpSocket::RegisterCallback(SOCKETHOOK callback)
{
    m_callback = callback;
}

int TcpSocket::GetSocket() const
{
    return m_recvSock;
}

int TcpSocket::GetSize() const
{
    return m_recvSize;
}

void TcpSocket::Finish()
{
    m_running = false;
    // 关闭监听 socket 以立即释放端口，同时解除 accept() 阻塞
    if (m_listenSock >= 0) {
        shutdown(m_listenSock, SHUT_RDWR);
        close(m_listenSock);
        m_listenSock = -1;
    }
    // 关闭当前已接受的连接
    if (m_recvSock > 0) {
        shutdown(m_recvSock, SHUT_RDWR);
        close(m_recvSock);
        m_recvSock = 0;
    }
}

TcpSocket::~TcpSocket()
{
    Finish();
}
