//
// Created by Shenyrion on 2022/5/2.
//

#ifndef DEVIDROID_TCPSOCKET_H
#define DEVIDROID_TCPSOCKET_H

#include <cstdint>

class TcpSocket;

typedef int(*SOCKETHOOK)(uint8_t*, size_t);

class TcpSocket {
public:
    TcpSocket() : m_recvSize(1024) {};

    void RegisterCallback(SOCKETHOOK);

    int Start(unsigned short port);

    int GetSocket() const;

    int GetSize() const;

    void Finish();

private:
    int Reciever(SOCKETHOOK callback) const;
    const int m_recvSize;
    SOCKETHOOK m_callback = nullptr;
    int m_recvSock = 0;
    volatile bool m_running = false;
};


#endif //DEVIDROID_TCPSOCKET_H
