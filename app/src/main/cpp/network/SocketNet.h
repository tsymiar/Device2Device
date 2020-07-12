//
// Created by tsymiar on 2020-05-10.
//

#ifndef DEVIDROID_SOCKETNET_H
#define DEVIDROID_SOCKETNET_H

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

class SocketNet {
private:
    int m_conv = 0x11223344;
    int m_socket = -1;
    std::string m_ip = {};
    int m_port = 0;
    int m_flag = false;
    NetProtocol protocol{};
    struct sockaddr_in m_peer{};
    const uint32_t SLICE_LEN = 1024;
    const uint32_t m_proSize = sizeof(NetProtocol);
    const uint32_t m_addLen = sizeof(struct sockaddr);
public:
    SocketNet();

    SocketNet(const std::string &, int);

    int Sender(const char *, size_t);

    int Receiver(char *, int);

private:
    int SliceSend(const char *, size_t);
};

#endif //DEVIDROID_SOCKETNET_H
