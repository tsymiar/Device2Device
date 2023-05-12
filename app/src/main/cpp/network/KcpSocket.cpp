#include <sys/types.h>
#include <sys/socket.h>
#include <pthread.h>
#include <arpa/inet.h>
#include "KcpSocket.h"

#define KCP_CONV_VAL 0x123

int udpOutput(const char* buf, int len, ikcpcb* kcp, void* user) {

    if (user == nullptr)
        return -1;
    auto* msg = (stKcpMsg*)user;
    ssize_t n = ::sendto(msg->sockfd, buf, len, 0, (struct sockaddr*)&msg->addr, sizeof(struct sockaddr_in));
    if (n >= 0) {
        if (!msg->isClient)
            printf("udpOutPut-sendto: %zd bytes [%s]\n", n, buf + 24);//24字节的KCP头部
        return n;
    } else {
        printf("udpOutput %s: %zd bytes sendto fail: %s\n", (msg->isClient ? "client" : "server"), n, strerror(errno));
        return -1;
    }
}

void init(stKcpMsg* msg)
{
    ikcpcb* kcp = ikcp_create(KCP_CONV_VAL, (void*)msg);
    kcp->output = udpOutput;
    ikcp_nodelay(kcp, 0, 1, 1, 0); // (kcp1, 0, 10, 0, 0); 1, 10, 2, 1
    ikcp_wndsize(kcp, 128, 128);
    msg->pkcp = kcp;
}

int initKcpSock(stKcpMsg* msg)
{
    if (msg == nullptr) {
        printf("stKcpMsg is null\n");
        return -1;
    }

    if (!msg->isClient) {
        msg->pkcp = nullptr;
        init(msg);
    }

    msg->sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (msg->sockfd < 0) {
        perror("socket");
        return -2;
    }

    bzero(&msg->addr, (unsigned long)sizeof(msg->addr));
    msg->addr.sin_family = AF_INET;
    msg->addr.sin_port = htons(msg->port);
    if (!msg->isClient) {
        msg->addr.sin_addr.s_addr = htonl(INADDR_ANY);//INADDR_ANY
        if (bind(msg->sockfd, (struct sockaddr*)&(msg->addr), sizeof(struct sockaddr_in)) < 0) {
            perror("bind");
            return -3;
        }
    } else {
        msg->addr.sin_addr.s_addr = inet_addr((char*)msg->ipstr);
        bzero(msg->content, sizeof(msg->content));
        char Msg[] = "Client:Hello!";
        memcpy(msg->content, Msg, sizeof(Msg));
    }

    if (msg->isClient) {
        init(msg);
        printf("KCP client init(%d) to %s:%d success\n", msg->sockfd, msg->ipstr, msg->port);
    } else {
        printf("KCP server init(%d) port: %d success\n", msg->sockfd, msg->port);
    }

    return 0;
}

void startClient(stKcpMsg* msg)
{
    if (msg == nullptr) {
        printf("stKcpMsg is null\n");
        return;
    }
#ifdef _KCP_TEST_
    int no = 0;
#endif
    int seq = 0;
    int state;
    char buffer[KCP_MSG_LEN + 24] = { 0 };
    socklen_t len = sizeof(struct sockaddr_in);
    while (1) {
        isleep(1);
        //ikcp_update(ikcp_flush，ikcp_flush) send data by UDP
        ikcp_update(msg->pkcp, iclock());

        if (seq < 500) {
            char test[KCP_MSG_LEN] = { 0 }; // first time
            memset(test, 97, sizeof(test));
            state = ikcp_send(msg->pkcp, test, (int)sizeof(test));
            seq++;
            printf("ikcp_send request [%s] len=%d state = %d seq = %d\n", test, (int)sizeof(test), state, seq);
        }

        ssize_t n = ::recvfrom(msg->sockfd, buffer, (KCP_MSG_LEN + 24), MSG_DONTWAIT, (struct sockaddr*)&msg->addr, &len);
        if (n < 0) {
            continue;
        }
        printf("UDP receive: %zd msg =%s\n", n, buffer + 24);

        // 预接收数据:调用ikcp_input将裸数据交给KCP，这些数据有可能是KCP控制报文，并不是我们要的数据。 
        // kcp接收到下层协议UDP传进来的数据底层数据buffer转换成kcp的数据包格式
        state = ikcp_input(msg->pkcp, buffer, n);
        if (state < 0) {
            //printf("ikcp_input state = %d\n", state);
            continue;
        }
        // ikcp_update(msg->pkcp,iclock());//不是调用一次两次就起作用，要loop调用

        while (1) {
            // kcp将接收到的kcp数据包还原成之前kcp发送的buffer数据		
            state = ikcp_recv(msg->pkcp, buffer, n);
            if (state < 0) {
                // printf("ikcp_recv state = %d\n", state);
                break;
            }
        }
        printf("receive from %s:%d\n", inet_ntoa(msg->addr.sin_addr), ntohs(msg->addr.sin_port));

#ifdef _KCP_TEST_
        if (strcmp(buffer, "Conn-OK") == 0) {
            printf("Data from Server-> %s\n", buffer);
            state = ikcp_send(msg->pkcp, msg->content, sizeof(msg->content));//strlen(msg->content)+1
            printf("Client reply -> size %d, state %d, [%s]\n", msg->content, (int)sizeof(msg->content), state);
            no++;
            printf("ikcp_send No.[%d]\n", no);
        }

        if (strcmp(buffer, "Server:Hello!") == 0) {
            printf("Data from Server-> %s\n", buffer);
            state = ikcp_send(msg->pkcp, msg->content, sizeof(msg->content));
            no++;
            printf("ikcp_send No.[%d] %d\n", no, state);
        }
#endif
    }
}

[[noreturn]] void startServer(stKcpMsg* msg)
{
#ifdef _KCP_TEST_
    int no = 0;
#endif
    int rcvd = 0;
    int udps = 0;
    socklen_t len = sizeof(struct sockaddr_in);
    const int bufSize = KCP_MSG_LEN + 24;
    char buffer[bufSize] = { 0 };
    while (1) {
        isleep(1);
        ikcp_update(msg->pkcp, iclock());
        memset(buffer, 0, bufSize);

        //处理收消息
        ssize_t n = ::recvfrom(msg->sockfd, buffer, bufSize, MSG_DONTWAIT, (struct sockaddr*)&msg->clientAddr, &len);
        if (n < 0)//检测是否有UDP数据包: kcp头部+data
            continue;
        udps++;
        printf("UDP接收 大小= %zd udps= %d \n", n, udps);

        //预接收数据:调用ikcp_input将裸数据交给KCP，这些数据有可能是KCP控制报文，并不是我们要的数据。 
        //kcp接收到下层协议UDP传进来的数据底层数据buffer转换成kcp的数据包格式
        int state = ikcp_input(msg->pkcp, buffer, n);

        if (state < 0)//检测ikcp_input对buffer是否提取到真正的数据	
        {
            printf("ikcp_input fail, state = %d\n", state);
            continue;
        }

        // while(1)
        // {
            //kcp将接收到的kcp数据包还原成之前kcp发送的buffer数据
        state = ikcp_recv(msg->pkcp, buffer, n);//从 buf中 提取真正数据，返回提取到的数据大小
        //	if(state < 0)//检测ikcp_recv提取到的数据
        //		break;
        // }
        rcvd++;
        printf("KCP交互 %s:%d rcvd = %d msg = %s\n", inet_ntoa(msg->clientAddr.sin_addr), ntohs(msg->clientAddr.sin_port), rcvd, buffer);
#ifdef _KCP_TEST_
        //发消息
        if (strcmp(buffer, "Conn") == 0) {
            //kcp提取到真正的数据
            printf("[Conn]  Data from Client-> %s\n", buffer);

            //kcp收到连接请求包，则回复确认连接包
            char cnn[] = "Conn-OK";

            //ikcp_send只是把数据存入发送队列，没有对数据加封kcp头部数据
            //应该是在kcp_update里面加封kcp头部数据
            //ikcp_send把要发送的buffer分片成KCP的数据包格式，插入待发送队列中。
            state = ikcp_send(msg->pkcp, cnn, (int)sizeof(cnn));
            printf("Server reply -> 内容[%s] 字节[%d] state = %d\n", cnn, (int)sizeof(cnn), state);

            no++;
            printf("第[%d]次发\n", no);
        }

        if (strcmp(buffer, "Client:Hello!") == 0) {
            //kcp提取到真正的数据
            printf("[Hello]  Data from Client-> %s\n", buffer);
            //kcp收到交互包，则回复
            ikcp_send(msg->pkcp, msg->content, sizeof(msg->content));
            no++;
            printf("第[%d]次发\n", no);
        }
#endif
    }
}

void uninitKcpSock(stKcpMsg* msg)
{
    if (msg != nullptr) {
        close(msg->sockfd);
        ikcp_release(msg->pkcp);
    }
}

int main(int argc, char* argv[])
{
    stKcpMsg msg;
    if (argc < 2) {
        printf("Usage:\n %s [ip/port] (port)\n", argv[0]);
        return -1;
    } else {
        if (argc >= 3) {
            msg.ipstr = (unsigned char*)argv[1];
            msg.port = atoi(argv[2]);
            msg.isClient = true;
        } else {
            msg.port = atoi(argv[1]);
            msg.isClient = false;
        }
    }

    initKcpSock(&msg);

    if (msg.isClient)
        startClient(&msg);
    else
        startServer(&msg);

    uninitKcpSock(&msg);

    return 0;
}
