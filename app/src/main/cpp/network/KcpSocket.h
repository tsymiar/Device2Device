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

int initKcpSock(stKcpMsg* msg);

void startClient(stKcpMsg* msg);

[[noreturn]] void startServer(stKcpMsg* msg);
void uninitKcpSock(stKcpMsg* msg);
