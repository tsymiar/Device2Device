#include <unistd.h>
#include <netdb.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <cstdlib>
#include <cstdio>
#include <string>

#ifndef LOG_TAG
#define LOG_TAG "SocketNet"
#endif

#include <utils/logger.h>

#include "SocketNet.h"

constexpr const int LOCAL_PORT = 9999;

SocketNet::SocketNet()
{
}

SocketNet::SocketNet(std::string _ip, int _port)
{
    this->m_ip = _ip;
    this->m_port = _port;
    LOGI("construct of SocketNet, %s:%d.", _ip.c_str(), _port);
}

int SocketNet::Init()
{
    m_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (m_socket < 0) {
        LOGE("socket: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_in local;
    local.sin_family = AF_INET;
    local.sin_port = htons(LOCAL_PORT);
    local.sin_addr.s_addr = htonl(INADDR_ANY);

    if (bind(m_socket, (struct sockaddr *) &local, sizeof(local)) < 0) {
        LOGE("bind: %s", strerror(errno));
        return -1;
    }
    LOGI("SocketNet Init success");
    return 0;
}

int SocketNet::Send(const char *sendBuff, size_t length)
{
    m_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (m_socket < 0) {
        LOGE("socket: %s", strerror(errno));
        return -1;
    }
    struct sockaddr_in peer;
    /* 设置远端的IP地址 */
    peer.sin_family = AF_INET;
    peer.sin_port = htons(this->m_port);
    peer.sin_addr.s_addr = inet_addr(this->m_ip.c_str());

    int iRet = sendto(m_socket, (char *) sendBuff, length, 0, (struct sockaddr *) &peer,
                      sizeof(struct sockaddr));
    if (iRet > 0)
        LOGI("Udp send: %s", sendBuff);
    else
        LOGI("Udp send error![%d]", iRet);
    close(m_socket);
    return 0;
}

int SocketNet::Recv(char *rcvBuff, int len)
{
    struct sockaddr_in remote;
    socklen_t size = sizeof(remote);
    while (1) {
        ssize_t _s = recvfrom(m_socket, rcvBuff, static_cast<size_t>(len), 0, (struct sockaddr *) &remote, &size);
        if (_s > 0) {
            rcvBuff[_s] = '\0';
            LOGI("client:[%s:%d]  %s, len = %d.", inet_ntoa(remote.sin_addr),
                 ntohs(remote.sin_port), rcvBuff, strlen(rcvBuff));
        } else if (_s == 0) {
            LOGI("client close");
            break;
        } else
            break;
    }
    close(m_socket);
    return 0;
}
