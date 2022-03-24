#include <unistd.h>
#include <netdb.h>
#include <fcntl.h>
#include <cstdlib>
#include <cstdio>
#include <string>

#ifndef LOG_TAG
#define LOG_TAG "UdpSocket"
#endif

#include <utils/logging.h>
#include <cerrno>

#include "UdpSocket.h"

constexpr const int LOCAL_PORT = 8899;

UdpSocket::UdpSocket() = default;

UdpSocket::UdpSocket(const std::string &_ip, int _port)
{
    this->m_ip = _ip;
    this->m_port = _port;
    LOGI("construct of UdpSocket, %s:%d.", _ip.c_str(), _port);
}

int UdpSocket::Sender(const char *sendBuff, size_t length)
{
    m_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (m_socket < 0) {
        LOGE("socket: %s", strerror(errno));
        return -1;
    }
    struct sockaddr_in peer{};
    /* 设置远端的IP地址 */
    peer.sin_family = AF_INET;
    peer.sin_port = htons(this->m_port);
    peer.sin_addr.s_addr = inet_addr(this->m_ip.c_str());
    m_peer = peer;
    protocol.id++;
    int opt = 1;
    setsockopt(m_socket, SOL_SOCKET, SO_REUSEADDR, (const void *) &opt, sizeof(opt));

    size_t total = length + m_proSize;
    auto *message = (uint8_t *) malloc(total);
    memset(message, 0, total);
    memcpy(message, &protocol, m_proSize);
    memcpy(message + m_proSize, sendBuff, length);

    int iRet = sendto(m_socket, (char *) message, total, 0, (struct sockaddr *) &peer, m_addLen);
    if (iRet > 0)
        LOGI("Udp send(%d):[%s]", iRet, message + m_proSize);
    else {
        if ((iRet = SendBySlice(sendBuff, length)) < 0)
            LOGE("Udp send(%d) error!", iRet);
    }
    close(m_socket);
    free(message);
    return 0;
}

int UdpSocket::Receiver(char *rcvBuff, int len, void(*callback)(char*))
{
    if (m_socket != -1 && m_flag) {
        LOGE("socket %d already in receiving.", m_socket);
        return m_socket;
    }

    m_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (m_socket < 0) {
        LOGE("socket: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_in local{};
    local.sin_family = AF_INET;
    local.sin_port = htons(LOCAL_PORT);
    local.sin_addr.s_addr = htonl(INADDR_ANY);
    int opt = 1;
    setsockopt(m_socket, SOL_SOCKET, SO_REUSEADDR, (const void *) &opt, sizeof(opt));

    if (bind(m_socket, (struct sockaddr *) &local, sizeof(local)) < 0) {
        LOGE("bind: %s", strerror(errno));
        return -1;
    }
    LOGI("UdpSocket Receiver bind success");

    fd_set fds;
    int maxFdp = m_socket + 1;
    struct sockaddr_in remote{};
    struct timeval timeout{};
    socklen_t size = sizeof(remote);
    while (!m_flag) {
        int _s = recvfrom(m_socket, rcvBuff, static_cast<size_t>(len), 0,
                          (struct sockaddr *) &remote, &size);
        if (_s > 0) {
            rcvBuff[_s] = '\0';
            LOGI("client:[%s:%d]len = %d:[%s].", inet_ntoa(remote.sin_addr),
                 ntohs(remote.sin_port), _s, rcvBuff + m_proSize);
            if (callback != nullptr) {
                size_t msg = strlen(rcvBuff + m_proSize) + 1;
                if (msg > _s)
                    rcvBuff[m_proSize + _s] = '\0';
                callback(rcvBuff + m_proSize);
            }
        } else if (_s == 0) {
            LOGI("client close");
        } else
            break;
        FD_ZERO(&fds);
        FD_SET(m_socket, &fds);
        timeout.tv_usec = 0;
        timeout.tv_sec = 3;
        if (0 < select(maxFdp, &fds, nullptr, nullptr, &timeout)) {
            if (FD_ISSET(m_socket, &fds)) {
                // TODO
            }
        }
    }
    close(m_socket);
    return 0;
}

int UdpSocket::SendBySlice(const char *sliceBuffer, size_t length)
{
    int iRet = 0;
    protocol.s_idx = 0;
    protocol.size = length;
    uint64_t offset = 0;
    uint64_t slice = SLICE_LEN;
    uint64_t remain = length;

    while (protocol.remain > 0) {
        uint32_t size = slice + m_proSize;
        auto *message = new(std::nothrow) uint8_t[size];
        memset(message, 0, size);

        protocol.slice = slice;
        protocol.s_size = size;
        protocol.offset = offset;
        protocol.remain = remain;

        memcpy(message, &protocol, m_proSize);
        memcpy(message + m_proSize, sliceBuffer + offset, slice);

        iRet = sendto(m_socket, message, size, 0, (struct sockaddr *) &m_peer, m_addLen);
        delete[] message;

        remain = length - slice;
        if (offset + slice <= length)
            offset += slice;
        else
            offset = slice = length - offset;
        protocol.s_idx++;
    }
    return iRet;
}
