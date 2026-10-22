#include <unistd.h>
#include <netdb.h>
#include <fcntl.h>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <string>
#include <algorithm>
#include <new>
#include <netinet/in.h>
#include <cerrno>

#ifndef LOG_TAG
#define LOG_TAG "UdpSocket"
#endif

#include <utils/logging.h>

#include "UdpSocket.h"

UdpSocket::UdpSocket() = default;

UdpSocket::UdpSocket(int _port)
{
    this->m_port = _port;
}

UdpSocket::UdpSocket(const std::string &_ip, int _port)
{
    this->m_ip = _ip;
    this->m_port = _port;
    LOGI("construct of UdpSocket, %s:%d.", _ip.c_str(), _port);
}

UdpSocket::~UdpSocket()
{
    closeSocket();
}

void UdpSocket::setPort(int port)
{
    m_port = port;
}

int UdpSocket::port() const
{
    return m_port;
}

void UdpSocket::enableBroadcast(bool on)
{
    m_broadcast = on;
}

void UdpSocket::setMulticastGroup(const std::string& group)
{
    m_multicastGroup = group;
    m_multicast = true;
}

void UdpSocket::setRecvTimeoutMs(int ms)
{
    m_recvTimeoutMs = ms;
}

bool UdpSocket::isOpen() const
{
    return m_socket >= 0;
}

void UdpSocket::stop()
{
    m_flag = true;
}

void UdpSocket::restart()
{
    m_flag = false;
}

void UdpSocket::closeSocket()
{
    if (m_socket >= 0) {
        ::close(m_socket);
        m_socket = -1;
    }
    m_bound = false;
}

int UdpSocket::open()
{
    if (m_socket >= 0) {
        return 0;   // 已经开了
    }
    m_flag = false;

    int fd = ::socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        LOGE("socket: %s", strerror(errno));
        return -1;
    }
    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, (const void*)&opt, sizeof(opt));
    if (m_broadcast) {
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, (const void*)&opt, sizeof(opt));
    }
    if (m_recvTimeoutMs > 0) {
        struct timeval tv {};
        tv.tv_sec = m_recvTimeoutMs / 1000;
        tv.tv_usec = (m_recvTimeoutMs % 1000) * 1000;
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, (const void*)&tv, sizeof(tv));
    }

    struct sockaddr_in local {};
    local.sin_family = AF_INET;
    local.sin_port = htons((uint16_t)m_port);
    local.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(fd, (struct sockaddr*)&local, sizeof(local)) < 0) {
        LOGE("bind %d: %s", m_port, strerror(errno));
        ::close(fd);
        return -2;
    }

    if (m_multicast && !m_multicastGroup.empty()) {
        struct ip_mreq mreq {};
        mreq.imr_multiaddr.s_addr = inet_addr(m_multicastGroup.c_str());
        mreq.imr_interface.s_addr = htonl(INADDR_ANY);
        if (setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, sizeof(mreq)) < 0) {
            LOGE("join multicast %s fail: %s", m_multicastGroup.c_str(), strerror(errno));
        } else {
            LOGI("joined multicast group %s", m_multicastGroup.c_str());
        }
    }

    m_socket = fd;
    m_bound = true;
    LOGI("UdpSocket open 0.0.0.0:%d (broadcast=%d, recvTimeout=%dms)",
        m_port, (int)m_broadcast, m_recvTimeoutMs);
    return 0;
}

int UdpSocket::sendTo(const char* ip, int port, const void* data, size_t length)
{
    if (m_socket < 0) {
        LOGE("sendTo(%s:%d) before open()", ip, port);
        return -1;
    }
    if (data == nullptr || length == 0) {
        return 0;
    }
    struct sockaddr_in dst {};
    dst.sin_family = AF_INET;
    dst.sin_port = htons((uint16_t)port);
    dst.sin_addr.s_addr = ::inet_addr(ip);
    ssize_t n = ::sendto(m_socket, data, length, 0,
        (struct sockaddr*)&dst, sizeof(dst));
    if (n < 0) {
        LOGE("sendto %s:%d: %s", ip, port, strerror(errno));
        return -2;
    }
    return (int)n;
}

int UdpSocket::receive(char* buf, int len, std::string* peerIp, int* peerPort)
{
    if (m_socket < 0 || m_flag || buf == nullptr || len <= 0) {
        return -1;
    }
    struct sockaddr_in from {};
    socklen_t fromLen = sizeof(from);
    ssize_t n = ::recvfrom(m_socket, buf, (size_t)len, 0,
        (struct sockaddr*)&from, &fromLen);
    if (n < 0) {
        // 设了超时之后的 EAGAIN 就是"这一轮没收到"，不是错误
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return 0;
        }
        return -1;
    }
    if (n == 0) {
        return 0;
    }
    // 留一个字节给结束符，别越界
    if ((int)n < len) {
        buf[n] = '\0';
    } else {
        buf[len - 1] = '\0';
    }
    if (peerIp != nullptr) {
        char addr[INET_ADDRSTRLEN] = { 0 };
        if (::inet_ntop(AF_INET, &from.sin_addr, addr, sizeof(addr)) != nullptr) {
            peerIp->assign(addr);
        } else {
            peerIp->clear();
        }
    }
    if (peerPort != nullptr) {
        *peerPort = ntohs(from.sin_port);
    }
    return (int)n;
}

int UdpSocket::Receiver(char *rcvBuff, int len, void(*callback)(char*))
{
    if (m_flag) {
        return -1;
    }
    // 老用法是临时建一个 UdpSocket(port) 就收；新用法可能已经 open() 过了
    const bool ownSocket = !m_bound;
    if (ownSocket && open() < 0) {
        return -1;
    }

    while (!m_flag) {
        std::string from;
        int port = 0;
        int n = receive(rcvBuff, len, &from, &port);
        if (n < 0) {
            break;
        }
        if (n == 0) {
            // 未设置超时则 recvfrom 会一直阻塞，能走到这里只可能是超时
            if (m_recvTimeoutMs > 0) {
                continue;
            }
            break;
        }
        // 带 NetProtocol 头发的包，正文在头之后
        char* text = rcvBuff + m_proSize;
        int textLen = n - (int)m_proSize;
        if (textLen < 0) {
            textLen = 0;
        }
        if (m_proSize + (size_t)textLen < (size_t)len) {
            text[textLen] = '\0';
        } else if (len > 0) {
            rcvBuff[len - 1] = '\0';
        }
        if (callback != nullptr) {
            callback(text);
        }
    }

    if (ownSocket) {
        closeSocket();
    }
    return 0;
}

int UdpSocket::Sender(const char* sendBuff, size_t length)
{
    // 已 open() 时复用该 socket，不重复创建，也不在发送结束后关闭
    const bool ownSocket = (m_socket < 0);
    if (ownSocket) {
        int fd = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (fd < 0) {
            LOGE("socket: %s", strerror(errno));
            return -1;
        }
        int flag = 1;
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, (const void*)&flag, sizeof(flag));
        if (m_broadcast) {
            setsockopt(fd, SOL_SOCKET, SO_BROADCAST, (const void*)&flag, sizeof(flag));
        }
        m_socket = fd;
    }

    struct sockaddr_in peer {};
    /* 设置远端的IP地址 */
    peer.sin_family = AF_INET;
    peer.sin_port = htons((uint16_t)this->m_port);
    peer.sin_addr.s_addr = inet_addr(this->m_ip.c_str());
    m_peer = peer;
    protocol.id++;
    int opt = 1;
    setsockopt(m_socket, SOL_SOCKET, SO_REUSEADDR, (const void *) &opt, sizeof(opt));

    size_t total = length + m_proSize;
    auto* message = (uint8_t*)malloc(total);
    if (message == nullptr) {
        if (ownSocket) {
            ::close(m_socket);
            m_socket = -1;
        }
        return -1;
    }
    memset(message, 0, total);
    memcpy(message, &protocol, m_proSize);
    memcpy(message + m_proSize, sendBuff, length);

    int iRet = (int)::sendto(m_socket, (char*)message, total, 0,
        (struct sockaddr*)&peer, m_addLen);
    if (iRet > 0) {
        LOGI("Udp send(%d):[%s]", iRet, message + m_proSize);
    } else {
        if ((iRet = SendBySlice(sendBuff, length)) < 0) {
            LOGE("Udp send(%d) error!", iRet);
        }
    }
    if (ownSocket) {
        ::close(m_socket);
        m_socket = -1;
    }
    free(message);
    return iRet < 0 ? -1 : 0;
}

int UdpSocket::SendBySlice(const char *sliceBuffer, size_t length)
{
    int iRet = 0;
    protocol.s_idx = 0;
    protocol.size = length;
    uint64_t offset = 0;
    uint64_t slice = SLICE_LEN;
    protocol.remain = length;

    while (protocol.remain > 0) {
        uint64_t actualSlice = std::min(slice, protocol.remain);
        uint32_t size = (uint32_t)(actualSlice + m_proSize);
        auto* message = new(std::nothrow) uint8_t[size];
        if (message == nullptr) {
            LOGE("SendBySlice malloc %u failed!", size);
            return -1;
        }
        memset(message, 0, size);

        protocol.slice = actualSlice;
        protocol.s_size = size;
        protocol.offset = offset;

        memcpy(message, &protocol, m_proSize);
        memcpy(message + m_proSize, sliceBuffer + offset, (size_t)actualSlice);

        iRet = (int)::sendto(m_socket, message, size, 0,
            (struct sockaddr*)&m_peer, m_addLen);
        delete[] message;

        offset += actualSlice;
        protocol.remain -= actualSlice;
        if (protocol.remain < slice) {
            slice = protocol.remain;
        }
        protocol.s_idx++;
    }
    return iRet;
}
