#include <sys/types.h>
#include <sys/socket.h>
#include <pthread.h>
#include <arpa/inet.h>
#include "KcpSocket.h"
#ifndef LOG_TAG
#define LOG_TAG "KcpSocket"
#endif
#include <utils/logging.h>

#define KCP_CONV_VAL 0x123

int udpOutput(const char* buff, int len, ikcpcb* kcp, void* user) {

    if (user == nullptr)
        return -1;
    auto* msg = (stKcpMsg*)user;
    ssize_t n = ::sendto(msg->sockfd, buff, len, 0, (struct sockaddr*)&msg->addr, sizeof(struct sockaddr_in));
    if (n >= 0) {
        if (!msg->isClient)
            LOGI("udpOutPut-sendto: %zd bytes [%08x]\n", n, buff); //24字节的KCP头部
        return n;
    } else {
        LOGI("udpOutput %s: %zd bytes sendto fail: %s\n", (msg->isClient ? "client" : "server"), n, strerror(errno));
        return -1;
    }
}

static void setupKcp(stKcpMsg* msg)
{
    ikcpcb* kcp = ikcp_create(KCP_CONV_VAL, (void*)msg);
    kcp->output = udpOutput;
    ikcp_nodelay(kcp, 0, 1, 1, 0); // (kcp1, 0, 10, 0, 0); 1, 10, 2, 1
    ikcp_wndsize(kcp, 128, 128);
    msg->pkcp = kcp;
}


int KcpSocket::init(int port, bool client, const char* ip)
{
    m_kcpMsg.ipstr = (unsigned char*)ip;
    m_kcpMsg.port = port;
    m_kcpMsg.isClient = client;

    if (!m_kcpMsg.isClient) {
        m_kcpMsg.pkcp = nullptr;
        setupKcp(&m_kcpMsg);
    }

    m_kcpMsg.sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (m_kcpMsg.sockfd < 0) {
        perror("socket");
        return -2;
    }

    bzero(&m_kcpMsg.addr, (unsigned long)sizeof(m_kcpMsg.addr));
    m_kcpMsg.addr.sin_family = AF_INET;
    m_kcpMsg.addr.sin_port = htons(m_kcpMsg.port);
    if (!m_kcpMsg.isClient) {
        m_kcpMsg.addr.sin_addr.s_addr = htonl(INADDR_ANY);//INADDR_ANY
        if (bind(m_kcpMsg.sockfd, (struct sockaddr*)&(m_kcpMsg.addr), sizeof(struct sockaddr_in)) < 0) {
            perror("bind");
            return -3;
        }
    } else {
        m_kcpMsg.addr.sin_addr.s_addr = inet_addr((char*)m_kcpMsg.ipstr);
        bzero(m_kcpMsg.content, sizeof(m_kcpMsg.content));
        char Msg[] = "Client:Hello!";
        memcpy(m_kcpMsg.content, Msg, sizeof(Msg));
    }

    if (m_kcpMsg.isClient) {
        setupKcp(&m_kcpMsg);
        LOGI("KCP client init(%d) to %s:%d success\n", m_kcpMsg.sockfd, m_kcpMsg.ipstr, m_kcpMsg.port);
    } else {
        LOGI("KCP server init(%d) port: %d success\n", m_kcpMsg.sockfd, m_kcpMsg.port);
    }

    m_running = true;
    return 0;
}

void KcpSocket::startClient() const
{
    if (!m_running) {
        LOGE("you should init at first\n");
        return;
    }
#ifdef _KCP_TEST_
    int no = 0;
#endif
    int seq = 0;
    int state;
    char buffer[KCP_MSG_LEN + 24] = { 0 };
    socklen_t len = sizeof(struct sockaddr_in);
    while (m_running) {
        isleep(1);
        //ikcp_update(ikcp_flush，ikcp_flush) send data by UDP
        ikcp_update(m_kcpMsg.pkcp, iclock());

        if (seq < 500) {
            char test[KCP_MSG_LEN] = { 0 }; // first time
            memset(test, 97, sizeof(test));
            state = ikcp_send(m_kcpMsg.pkcp, test, (int)sizeof(test));
            seq++;
            LOGI("ikcp_send request len=%d seq=%d stat=%d: content[%s]\n", (int)sizeof(test), seq, state, test);
        }

        ssize_t n = ::recvfrom(m_kcpMsg.sockfd, buffer, (KCP_MSG_LEN + 24), MSG_DONTWAIT, (struct sockaddr*)&m_kcpMsg.addr, &len);
        if (n < 0) {
            continue;
        }
        LOGI("UDP receive: %zd msg =%s\n", n, buffer + 24);

        // 预接收数据:调用ikcp_input将裸数据交给KCP，这些数据有可能是KCP控制报文，并不是我们要的数据。
        // kcp接收到下层协议UDP传进来的数据底层数据buffer转换成kcp的数据包格式
        state = ikcp_input(m_kcpMsg.pkcp, buffer, n);
        if (state < 0) {
            //printf("ikcp_input state = %d\n", state);
            continue;
        }
        // ikcp_update(m_kcpMsg.pkcp,iclock());//不是调用一次两次就起作用，要loop调用

        while (m_running) {
            // kcp将接收到的kcp数据包还原成之前kcp发送的buffer数据
            state = ikcp_recv(m_kcpMsg.pkcp, buffer, n);
            if (state < 0) {
                // printf("ikcp_recv state = %d\n", state);
                break;
            }
        }
        LOGI("receive from %s:%d\n", inet_ntoa(m_kcpMsg.addr.sin_addr), ntohs(m_kcpMsg.addr.sin_port));

#ifdef _KCP_TEST_
        if (strcmp(buffer, "Conn-OK") == 0) {
            printf("Data from Server-> %s\n", buffer);
            state = ikcp_send(m_kcpMsg.pkcp, m_kcpMsg.content, sizeof(m_kcpMsg.content));//strlen(m_kcpMsg.content)+1
            printf("Client reply -> size %d, state %d, [%s]\n", m_kcpMsg.content, (int)sizeof(m_kcpMsg.content), state);
            no++;
            printf("ikcp_send No.[%d]\n", no);
        }

        if (strcmp(buffer, "Server:Hello!") == 0) {
            printf("Data from Server-> %s\n", buffer);
            state = ikcp_send(m_kcpMsg.pkcp, m_kcpMsg.content, sizeof(m_kcpMsg.content));
            no++;
            printf("ikcp_send No.[%d] %d\n", no, state);
        }
#endif
    }
}

void KcpSocket::startServer() const
{
    if (!m_running) {
        LOGE("you should init at first\n");
        return;
    }
#ifdef _KCP_TEST_
    int no = 0;
#endif
    int rcvd = 0;
    int udps = 0;
    socklen_t len = sizeof(struct sockaddr_in);
    const int bufSize = KCP_MSG_LEN + 24;
    char buffer[bufSize] = { 0 };
    while (m_running) {
        isleep(1);
        ikcp_update(m_kcpMsg.pkcp, iclock());
        memset(buffer, 0, bufSize);

        //处理收消息
        ssize_t n = ::recvfrom(m_kcpMsg.sockfd, buffer, bufSize, MSG_DONTWAIT, (struct sockaddr*)&m_kcpMsg.clientAddr, &len);
        if (n < 0)//检测是否有UDP数据包: kcp头部+data
            continue;
        udps++;
        LOGI("UDP接收 大小= %zd udps= %d \n", n, udps);

        //预接收数据:调用ikcp_input将裸数据交给KCP，这些数据有可能是KCP控制报文，并不是我们要的数据。
        //kcp接收到下层协议UDP传进来的数据底层数据buffer转换成kcp的数据包格式
        int state = ikcp_input(m_kcpMsg.pkcp, buffer, n);

        if (state < 0)//检测ikcp_input对buffer是否提取到真正的数据
        {
            LOGE("ikcp_input fail, state = %d\n", state);
            continue;
        }

        // while(1)
        // {
        //kcp将接收到的kcp数据包还原成之前kcp发送的buffer数据
        state = ikcp_recv(m_kcpMsg.pkcp, buffer, n);//从 buf中 提取真正数据，返回提取到的数据大小
        //	if(state < 0)//检测ikcp_recv提取到的数据
        //		break;
        // }
        rcvd++;
        LOGI("KCP交互 from %s:%d rcvd=%d stat=%d, head=0x%08x, content[%s]\n", inet_ntoa(m_kcpMsg.clientAddr.sin_addr), ntohs(m_kcpMsg.clientAddr.sin_port), rcvd, state, buffer, buffer + 24);
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
            state = ikcp_send(m_kcpMsg.pkcp, cnn, (int)sizeof(cnn));
            printf("Server reply -> 内容[%s] 字节[%d] state = %d\n", cnn, (int)sizeof(cnn), state);

            no++;
            printf("第[%d]次发\n", no);
        }

        if (strcmp(buffer, "Client:Hello!") == 0) {
            //kcp提取到真正的数据
            printf("[Hello]  Data from Client-> %s\n", buffer);
            //kcp收到交互包，则回复
            ikcp_send(m_kcpMsg.pkcp, m_kcpMsg.content, sizeof(m_kcpMsg.content));
            no++;
            printf("第[%d]次发\n", no);
        }
#endif
    }
}

void KcpSocket::destroy()
{
    m_running = false;
    usleep(1000);
    if (m_kcpMsg.sockfd != 0) {
        close(m_kcpMsg.sockfd);
    }
    if (m_kcpMsg.pkcp != nullptr) {
        ikcp_release(m_kcpMsg.pkcp);
    }
}

int test(int argc, char* argv[])
{
    const char* ipStr = nullptr;
    int port = 0;
    bool isClient = false;
    if (argc < 2) {
        printf("Usage:\n %s [ip/port] (port)\n", argv[0]);
        return -1;
    } else {
        char *end;
        if (argc >= 3) {
            ipStr = argv[1];
            port = (int)strtol(argv[2], &end, 10);
            isClient = true;
        } else {
            port = (int)strtol(argv[1], &end, 10);
            isClient = false;
        }
    }

    KcpSocket kcpSocket{};

    kcpSocket.init(port, isClient, ipStr);

    if (isClient)
        kcpSocket.startClient();
    else
        kcpSocket.startServer();

    kcpSocket.destroy();

    return 0;
}
