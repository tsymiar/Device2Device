//
// Created by Shenyrion on 2022/5/2.
//

#ifndef DEVICE2DEVICE_TCPSOCKET_H
#define DEVICE2DEVICE_TCPSOCKET_H

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
    int Receiver(SOCKETHOOK callback) const;
    const int m_recvSize;
    SOCKETHOOK m_callback = nullptr;
    int m_recvSock = 0;
    volatile bool m_running = false;
};

#endif //DEVICE2DEVICE_TCPSOCKET_H
