#include "KcpEmulator.h"

// 模拟网络
KcpEmulator *vnet;

class iterator;

// lostrate: 往返一周丢包率的百分比，默认 10%
// rttmin：rtt最小值，默认 60
// rttmax：rtt最大值，默认 125
KcpEmulator::KcpEmulator(int lostrate, int rttmin, int rttmax, int nmax) :
        r12(100), r21(100)
{
    current = iclock();
    this->lostrate = lostrate / 2;    // 上面数据是往返丢包率，单程除以2
    this->rttmin = rttmin / 2;
    this->rttmax = rttmax / 2;
    this->nmax = nmax;
    tx1 = tx2 = 0;
}

KcpEmulator::~KcpEmulator()
{
    DelayTunnel::iterator it;
    for (it = p12.begin(); it != p12.end(); it++) {
        delete *it;
    }
    for (it = p21.begin(); it != p21.end(); it++) {
        delete *it;
    }
    p12.clear();
    p21.clear();
}

// 发送数据
// peer - 端点0/1，从0发送，从1接收；从1发送从0接收
void KcpEmulator::Sender(int peer, const void *data, int size)
{
    if (peer == 0) {
        tx1++;
        if (r12.random() < lostrate) return;
        if ((int) p12.size() >= nmax) return;
    } else {
        tx2++;
        if (r21.random() < lostrate) return;
        if ((int) p21.size() >= nmax) return;
    }
    auto *pkt = new DelayPacket(size, data);
    current = iclock();
    IUINT32 delay = rttmin;
    if (rttmax > rttmin) delay += rand() % (rttmax - rttmin);
    pkt->setts(current + delay);
    if (peer == 0) {
        p12.push_back(pkt);
    } else {
        p21.push_back(pkt);
    }
}

int KcpEmulator::Receiver(int peer, void *data, int maxsize)
{
    DelayTunnel::iterator it;
    if (peer == 0) {
        it = p21.begin();
        if (p21.size() == 0) return -1;
    } else {
        it = p12.begin();
        if (p12.size() == 0) return -1;
    }
    DelayPacket *pkt = *it;
    current = iclock();
    if (current < pkt->ts()) return -2;
    if (maxsize < pkt->size()) return -3;
    if (peer == 0) {
        p21.erase(it);
    } else {
        p12.erase(it);
    }
    maxsize = pkt->size();
    memcpy(data, pkt->ptr(), maxsize);
    delete pkt;
    return maxsize;
}

// 模拟网络：模拟发送一个 udp包
int udp_output(const char *buf, int len, ikcpcb *kcp, void *user)
{
    union {
        int id;
        void *ptr;
    } parameter{};
    parameter.ptr = user;
    vnet->Sender(parameter.id, buf, len);
    return 0;
}

// 测试用例
void KcpEmulator::KcpRun(int mode)
{
    // 创建模拟网络：丢包率10%，Rtt 60ms~125ms
    vnet = new KcpEmulator(10, 60, 125);

    // 创建两个端点的 kcp对象，第一个参数 conv是会话编号，同一个会话需要相同
    // 最后一个是 user参数，用来传递标识
    ikcpcb *kcp1 = ikcp_create(0x11223344, (void *) 0);
    ikcpcb *kcp2 = ikcp_create(0x11223344, (void *) 1);

    // 设置kcp的下层输出，这里为 udp_output，模拟udp网络输出函数
    kcp1->output = udp_output;
    kcp2->output = udp_output;

    IUINT32 current = iclock();
    IUINT32 slap = current + 20;
    IUINT32 index = 0;
    IUINT32 next = 0;
    IINT64 sumrtt = 0;
    int count = 0;
    int maxrtt = 0;

    // 配置窗口大小：平均延迟200ms，每20ms发送一个包，
    // 而考虑到丢包重发，设置最大收发窗口为128
    ikcp_wndsize(kcp1, RECVWND, RECVWND);
    ikcp_wndsize(kcp2, RECVWND, RECVWND);

    // 判断测试用例的模式
    if (mode == 0) {
        // 默认模式
        ikcp_nodelay(kcp1, 0, 10, 0, 0);
        ikcp_nodelay(kcp2, 0, 10, 0, 0);
    } else if (mode == 1) {
        // 普通模式，关闭流控等
        ikcp_nodelay(kcp1, 0, 10, 0, 1);
        ikcp_nodelay(kcp2, 0, 10, 0, 1);
    } else {
        // 启动快速模式
        // 第二个参数 nodelay-启用以后若干常规加速将启动
        // 第三个参数 interval为内部处理时钟，默认设置为 10ms
        // 第四个参数 resend为快速重传指标，设置为2
        // 第五个参数 为是否禁用常规流控，这里禁止
        ikcp_nodelay(kcp1, 2, 10, 2, 1);
        ikcp_nodelay(kcp2, 2, 10, 2, 1);
        kcp1->rx_minrto = 10;
        kcp1->fastresend = 1;
    }

    char buffer[2000];
    int hr;

    IUINT32 ts1 = iclock();

    while (1) {
        isleep(1);
        current = iclock();
        ikcp_update(kcp1, iclock());
        ikcp_update(kcp2, iclock());

        // 每隔 20ms，kcp1发送数据
        for (; current >= slap; slap += 20) {
            ((IUINT32 *) buffer)[0] = index++;
            ((IUINT32 *) buffer)[1] = current;

            // 发送上层协议包
            ikcp_send(kcp1, buffer, 8);
        }

        // 处理虚拟网络：检测是否有udp包从p1->p2
        while (1) {
            hr = vnet->Receiver(1, buffer, 2000);
            if (hr < 0) break;
            // 如果 p2收到udp，则作为下层协议输入到kcp2
            ikcp_input(kcp2, buffer, hr);
        }

        // 处理虚拟网络：检测是否有udp包从p2->p1
        while (1) {
            hr = vnet->Receiver(0, buffer, 2000);
            if (hr < 0) break;
            // 如果 p1收到udp，则作为下层协议输入到kcp1
            ikcp_input(kcp1, buffer, hr);
        }

        // kcp2接收到任何包都返回回去
        while (1) {
            hr = ikcp_recv(kcp2, buffer, 10);
            // 没有收到包就退出
            if (hr < 0) break;
            // 如果收到包就回射
            ikcp_send(kcp2, buffer, hr);
        }

        // kcp1收到kcp2的回射数据
        while (1) {
            hr = ikcp_recv(kcp1, buffer, 10);
            // 没有收到包就退出
            if (hr < 0) break;
            IUINT32 sn = *(IUINT32 *) (buffer + 0);
            IUINT32 ts = *(IUINT32 *) (buffer + 4);
            IUINT32 rtt = current - ts;

            if (sn != next) {
                // 如果收到的包不连续
                printf("ERROR sn %d<->%d\n", (int) count, (int) next);
                return;
            }

            next++;
            sumrtt += rtt;
            count++;
            if (rtt > (IUINT32) maxrtt) maxrtt = rtt;

            printf("[RECV] mode=%d sn=%d rtt=%d\n", mode, (int) sn, (int) rtt);
        }
        if (next > 1000) break;
    }

    ts1 = iclock() - ts1;

    ikcp_release(kcp1);
    ikcp_release(kcp2);

    const char *names[3] = {"default", "normal", "fast"};
    printf("%s mode result (%dms):\n", names[mode], (int) ts1);
    printf("avgrtt=%d maxrtt=%d tx=%d\n", (int) (sumrtt / count), (int) maxrtt, (int) vnet->tx1);
    printf("press enter to next ...\n");
    char ch;
    scanf("%c", &ch);
}
