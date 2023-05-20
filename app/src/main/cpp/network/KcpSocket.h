#pragma once
#include "KcpEmulator.h"
#include <netinet/in.h>

#define KCP_MSG_LEN 1024

typedef struct {
    unsigned char* ipstr;
    int port;
    ikcpcb* pkcp;
    int sockfd;
    struct sockaddr_in addr;
    struct sockaddr_in clientAddr;
    bool isClient;
    char content[KCP_MSG_LEN];
} stKcpMsg;

class KcpSocket {
public:
    int init(int port, bool client = false, const char* ip = nullptr);
    void startClient() const;
    void startServer() const;
    void destroy();
private:
    bool m_running;
    stKcpMsg m_kcpMsg;
};
