//
// Created by tsymiar on 2020-05-10.
//

#ifndef DEVIDROID_SOCKETNET_H
#define DEVIDROID_SOCKETNET_H

#include <string>

class SocketNet {
private:
    int m_socket = -1;
    std::string m_ip = {};
    int m_port = 0;
public:
    SocketNet();

    SocketNet(std::string, int);

    int Init();

    int Send(const char *, size_t);

    int Recv(char *, int);
};


#endif //DEVIDROID_SOCKETNET_H
