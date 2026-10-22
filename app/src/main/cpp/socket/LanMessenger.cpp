#include <sys/types.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <chrono>
#include <cstring>
#include <ctime>
#include <time.h>
#include <sstream>
#include <vector>

#ifndef LOG_TAG
#define LOG_TAG "LanMessenger"
#endif
#include <utils/logging.h>
#include <message/Message.h>
#include "LanMessenger.h"

#define LAN_ANNOUNCE_INTERVAL_MS 1000
#define LAN_MAX_TEXT 900
#define LAN_RECV_BUF 2048
/** How many received ids to remember for duplicate suppression */
#define LAN_SEEN_CAP 200

namespace {

/** Announcement packet: type | self id | device name | text-message port */
const char* const kAnnounce = "D2DPEER";
/**
 * 对"喊话"的回应：字段与 kAnnounce 相同，只单播给喊话那一方。
 * 没有它，新上线的设备要等对方下一轮广播（秒级）才知道对方存在；
 * 有它，两边一个往返就互相认识。回应本身不再引发回应，否则会你一回我一发形成乒乓。
 */
const char* const kReply = "D2DPEEROK";
/**
 * Wire format of a text message:
 * D2DMSG | type | id | name | ip | port | selfId | body
 *
 * selfId 是发送方这一轮 start() 的会话标识（见 start() 里那段拼接）。没有它，
 * 去重只能按 "ip:id" 判：发送方的 app 进程重启后 id 又从 1 开始数，接收方的
 * 去重缓存里还留着上一轮的 "ip:1"，新消息就被当成重复悄悄丢掉 —— 发送端显示已送达，
 * 接收端什么也不显示。带上 selfId 之后，重启就是另一个发送方，不会撞车。
 *
 * 少一个字段（7 段）的是不带 selfId 的报文，按 7 段解析，去重也退回到 "ip:id"。
 */
const char* const kMsgMagic = "D2DMSG";
/**
 * Wire format of one piece of a split message:
 * D2DFRG | type | id | seq | total | name | ip | port | selfId | body
 *
 * selfId 的用处见上面 kMsgMagic。同样兼容少一个字段（不带 selfId）的报文。
 */
const char* const kFragMagic = "D2DFRG";

/** 报文里没带 selfId 时，去重键退回到用 ip */
const std::string kEmpty;

std::vector<std::string> split(const std::string& s, char sep, int maxParts)
{
    std::vector<std::string> out;
    std::string cur;
    for (size_t i = 0; i < s.size(); ++i) {
        char c = s[i];
        if (c == sep && (int)out.size() < maxParts - 1) {
            out.push_back(cur);
            cur.clear();
        } else {
            cur.push_back(c);
        }
    }
    out.push_back(cur);
    return out;
}

/**
 * base64 只有 A-Za-z0-9+/=，不可能出现 '|' 和换行，所以图片不需要 sanitize() 那套替换；
 * 反过来 sanitize() 会把输入截到 LAN_MAX_TEXT，对 base64 就是砍成断头。
 * 所以图片只校验字符集，既不替换也不截断。
 */
bool isBase64(const std::string& s)
{
    for (size_t i = 0; i < s.size(); ++i) {
        unsigned char c = (unsigned char)s[i];
        bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                  || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
        if (!ok) {
            return false;
        }
    }
    return true;
}

} // namespace

long long LanMessenger::nowMs()
{
    struct timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

std::string LanMessenger::sanitize(const std::string& in)
{
    std::string out;
    out.reserve(in.size());
    for (size_t i = 0; i < in.size(); ++i) {
        unsigned char c = (unsigned char)in[i];
        if (c == '\n' || c == '\r' || c == '|') {
            out.push_back(' ');   // separators and newlines would break the packet, map them to a space
        } else {
            out.push_back((char)c);
        }
    }
    // Trim on a UTF-8 boundary: cutting by byte would split a multi-byte character into garbage
    if ((int)out.size() > LAN_MAX_TEXT) {
        int end = LAN_MAX_TEXT;
        while (end > 0 && ((unsigned char)out[end] & 0xC0) == 0x80) {
            --end;
        }
        out = out.substr(0, end);
    }
    return out;
}

LanMessenger::LanMessenger() = default;

void LanMessenger::setDiscoveryPort(int port)
{
    m_discoveryPort = (port > 0) ? port : LAN_DISCOVERY_PORT;
}

void LanMessenger::setDiscoveryAddress(const std::string& addr)
{
    m_discoveryAddress = addr.empty() ? "255.255.255.255" : addr;
}

LanMessenger::~LanMessenger()
{
    stop();
}

LanMessenger& LanMessenger::instance()
{
    static LanMessenger self;
    return self;
}

static bool isMulticast(const std::string& addr);

int LanMessenger::start(const std::string& name, const std::string& selfIp, int port)
{
    if (m_running.load()) {
        return 1;
    }
    m_name = sanitize(name.empty() ? "Device2Device" : name);
    m_selfIp = selfIp;
    m_port = port > 0 ? port : LAN_CHAT_PORT;
    // Self id: used to filter out our own announcements (a socket receives what it sends)
    std::ostringstream oss;
    oss << ::getpid() << "-" << std::hex << (unsigned long)::time(nullptr) << "-" << std::rand();
    m_selfId = oss.str();

    // ---- Discovery UDP: reuses UdpSocket ----
    // Broadcast must be on, and the timeout lets discoveryLoop exit promptly instead of
    // blocking in recvfrom for a full timeout
    m_discovery.setPort(m_discoveryPort);
    m_discovery.enableBroadcast(true);
    if (isMulticast(m_discoveryAddress)) {
        m_discovery.setMulticastGroup(m_discoveryAddress);
    }
    m_discovery.setRecvTimeoutMs(500);
    if (m_discovery.open() < 0) {
        LOGE("discovery bind %d fail\n", m_discoveryPort);
        return -2;
    }

    // ---- Messaging: KCP over UDP ----
    // One socket bound to 0.0.0.0:m_port plays both roles: it takes whatever the LAN sends
    // to it, and sendTo() builds the peer's ikcp session on demand when we send out.
    // Echo has to be off, or two peers would bounce every message back and forth forever.
    if (m_kcp.init(m_port, false) < 0) {
        LOGE("kcp init %d fail\n", m_port);
        m_discovery.closeSocket();
        return -4;
    }
    m_kcp.setEchoEnabled(false);
    m_kcp.setRecvCallback(&LanMessenger::onKcpMessage, this);

    m_running.store(true);
    m_kcpThread = new std::thread([this]() { kcpLoop(); });
    m_announceThread = new std::thread([this]() { announceLoop(); });
    m_discoveryThread = new std::thread([this]() { discoveryLoop(); });
    m_sendThread = new std::thread([this]() { sendLoop(); });

    pushEvent(LAN_PEER, "CLEAR");
    pushEvent(LAN_MESSAGE,
              "LISTEN|" + m_selfIp + "|" + std::to_string(m_port) + "|" + m_name);
    LOGI("LAN messenger started: name=%s self=%s chat=%d discovery=%d\n",
         m_name.c_str(), m_selfIp.c_str(), m_port, m_discoveryPort);
    return 0;
}

void LanMessenger::stop()
{
    if (!m_running.load()) {
        return;
    }
    m_running.store(false);
    // Stop the discovery socket first: receive() returns at once, so discoveryLoop does not
    // have to wait out a full timeout
    m_discovery.stop();

    if (m_announceThread != nullptr) {
        if (m_announceThread->joinable()) m_announceThread->join();
        delete m_announceThread;
        m_announceThread = nullptr;
    }
    if (m_discoveryThread != nullptr) {
        if (m_discoveryThread->joinable()) m_discoveryThread->join();
        delete m_discoveryThread;
        m_discoveryThread = nullptr;
    }
    // ---- Stop the send thread ----
    // 先通知，空闲的循环立刻醒，不用等满 200ms；已在跑的发送走自己的线程，
    // 那时 KcpSocket 已停，它们会自己退出，不会碰已释放的 socket
    m_outCv.notify_one();
    if (m_sendThread != nullptr) {
        if (m_sendThread->joinable()) m_sendThread->join();
        delete m_sendThread;
        m_sendThread = nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(m_outMutex);
        m_outbox.clear();
    }
    {
        std::lock_guard<std::mutex> lock(m_pendingMutex);
        // No UNDELIVERED events here: the peer list is going away with the session anyway
        m_pending.clear();
        m_seenIds.clear();
        m_seenOrder.clear();
        m_frags.clear();
    }

    // ---- Stop the KCP socket ----
    // stop() makes the loop return inside one tick (recvfrom is non-blocking and the loop
    // sleeps 1ms), so the join does not have to wait out a timeout. destroy() then releases
    // every ikcp session and closes the socket.
    m_kcp.stop();
    if (m_kcpThread != nullptr) {
        if (m_kcpThread->joinable()) m_kcpThread->join();
        delete m_kcpThread;
        m_kcpThread = nullptr;
    }
    m_kcp.destroy();

    // Close sockets only once the threads are gone, so nobody is still using them
    m_discovery.closeSocket();

    {
        std::lock_guard<std::mutex> lock(m_peerMutex);
        m_peers.clear();
    }
    LOGI("LAN messenger stopped\n");
}

bool LanMessenger::isRunning() const
{
    return m_running.load();
}

void LanMessenger::pushEvent(int type, const std::string& text)
{
    Message::instance().setMessage(text, (MASSAGER)type);
}

void LanMessenger::announceOnce()
{
    std::string payload = std::string(kAnnounce) + "|" + m_selfId + "|"
                          + sanitize(m_name) + "|" + std::to_string(m_port);
    // Global broadcast plus this machine's /24 broadcast: some networks block 255.255.255.255,
    // so sending both reaches more peers
    m_discovery.sendTo("255.255.255.255", m_discoveryPort,
                       payload.data(), payload.size());

    struct in_addr self {};
    if (!m_selfIp.empty() && inet_pton(AF_INET, m_selfIp.c_str(), &self) == 1) {
        uint32_t host = ntohl(self.s_addr);
        uint32_t bcast = (host & 0xFFFFFF00u) | 0x000000FFu;
        struct in_addr bc {};
        bc.s_addr = htonl(bcast);
        char buf[INET_ADDRSTRLEN] = {0};
        if (inet_ntop(AF_INET, &bc, buf, sizeof(buf)) != nullptr
            && bcast != 0xFFFFFFFFu) {
            m_discovery.sendTo(buf, m_discoveryPort, payload.data(), payload.size());
        }
    }
    // 已知对端再单播一份：部分路由器开着 AP 隔离 / 过滤广播帧，广播到不了，单播却可以，
    // 而且 AP 会为省电休眠的对端缓存单播帧。对方认识我们，不会再回信，不产生额外流量。
    std::vector<std::string> targets;
    {
        std::lock_guard<std::mutex> lock(m_peerMutex);
        targets.reserve(m_peers.size());
        for (auto& kv : m_peers) {
            targets.push_back(kv.second.ip);
        }
    }
    for (const auto& ip : targets) {
        if (!ip.empty() && ip != m_selfIp) {
            m_discovery.sendTo(ip.c_str(), m_discoveryPort, payload.data(), payload.size());
        }
    }
    // 可选的组播/单播发现地址（如 224.224.224.224）：广播被部分网络过滤时，
    // 走组播或手动指定地址能绕过；空或就是广播地址时不重复发
    if (!m_discoveryAddress.empty() && m_discoveryAddress != "255.255.255.255") {
        m_discovery.sendTo(m_discoveryAddress.c_str(), m_discoveryPort, payload.data(), payload.size());
    }
}

void LanMessenger::replyOnce(const std::string& ip)
{
    if (!m_running.load() || ip.empty() || ip == m_selfIp) {
        return;
    }
    std::string payload = std::string(kReply) + "|" + m_selfId + "|"
                          + sanitize(m_name) + "|" + std::to_string(m_port);
    m_discovery.sendTo(ip.c_str(), m_discoveryPort, payload.data(), payload.size());
}

void LanMessenger::announceLoop()
{
    // 先连发三封再进周期：第一封常被 WiFi 省电或路由器的广播过滤吃掉，
    // 三封错开 200ms 至少有一封能到，刚点"开始"不用干等一整轮
    for (int i = 0; i < 3 && m_running.load(); ++i) {
        announceOnce();
        if (i < 2) {
            usleep(200 * 1000);
        }
    }
    while (m_running.load()) {
        for (int i = 0; i < LAN_ANNOUNCE_INTERVAL_MS / 100 && m_running.load(); ++i) {
            usleep(100 * 1000);
        }
        if (m_running.load()) {
            announceOnce();
        }
    }
}

static bool isMulticast(const std::string& addr) {
    struct in_addr a {};
    if (addr.empty() || inet_pton(AF_INET, addr.c_str(), &a) != 1) return false;
    uint32_t h = ntohl(a.s_addr);
    return (h >> 28) == 0x0Eu;   // 224.0.0.0/4
}

bool LanMessenger::onDiscoveryPacket(const std::string& payload, const std::string& fromIp)
{
    std::vector<std::string> parts = split(payload, '|', 4);
    if (parts.size() < 4) {
        return false;
    }
    const bool isAnnounce = (parts[0] == kAnnounce);
    if (!isAnnounce && parts[0] != kReply) {
        return false;
    }
    const std::string& id = parts[1];
    const std::string& name = parts[2];
    int port = ::atoi(parts[3].c_str());
    if (id == m_selfId || port <= 0) {
        return false;   // 自己的广播被 socket 收回来了，忽略
    }
    // 同一台机器上跑两个实例时对端地址就是本机：同样不显示，否则会看到"自己"
    if (fromIp == m_selfIp) {
        return false;
    }

    bool isNew = false;
    {
        std::lock_guard<std::mutex> lock(m_peerMutex);
        std::string key = fromIp + ":" + std::to_string(port);
        auto it = m_peers.find(key);
        isNew = (it == m_peers.end());
        if (isNew) {
            LanPeer peer;
            peer.ip = fromIp;
            peer.port = port;
            peer.name = name;
            peer.lastSeenMs = nowMs();
            m_peers[key] = peer;
        } else {
            it->second.lastSeenMs = nowMs();
            if (it->second.name != name) {
                it->second.name = name;
            }
        }
    }
    // 只回"喊话"不回"回应"：新上线的设备只广播了一封，在线的机器各回一封单播，
    // 它一个往返就能拿到全表，不必等我们的下一轮广播。回应再回就会两边无限乒乓。
    if (isAnnounce && isNew) {
        replyOnce(fromIp);
    }
    // 只有新设备才上报：否则每秒一封广播会把消息队列刷满
    if (isNew) {
        pushEvent(LAN_PEER, "ADD|" + fromIp + "|" + std::to_string(port) + "|" + name);
    }
    return true;
}

void LanMessenger::pruneLocked(long long now)
{
    for (auto it = m_peers.begin(); it != m_peers.end();) {
        if (now - it->second.lastSeenMs > LAN_PEER_TIMEOUT_MS) {
            pushEvent(LAN_PEER,
                      "DEL|" + it->second.ip + "|" + std::to_string(it->second.port)
                      + "|" + it->second.name);
            it = m_peers.erase(it);
        } else {
            ++it;
        }
    }
}

void LanMessenger::pushPeersLocked()
{
    for (auto& kv : m_peers) {
        pushEvent(LAN_PEER, "ADD|" + kv.second.ip + "|" + std::to_string(kv.second.port)
                            + "|" + kv.second.name);
    }
}

void LanMessenger::discoveryLoop()
{
    char buffer[LAN_RECV_BUF];
    long long lastPruneMs = nowMs();
    while (m_running.load()) {
        std::string fromIp;
        int fromPort = 0;
        int n = m_discovery.receive(buffer, sizeof(buffer) - 1, &fromIp, &fromPort);
        if (n < 0) {
            // -1 有两种：stop() 停了 socket，或者 recvfrom 真出错。后者一律 break 时，
            // 一次瞬时错误（UDP 上 ECONNREFUSED / ENETUNREACH 很常见）就会让发现线程死掉，
            // 从此收不到广播、对端全部超时掉线，且不重启 app 回不来。所以退避后继续收。
            if (!m_running.load()) {
                break;
            }
            LOGW("discovery recv error (%s), retry\n", strerror(errno));
            usleep(300 * 1000);
            continue;
        }
        if (n == 0) {
            // Nothing arrived before the timeout: prune stale peers, then go back to recvfrom
            std::lock_guard<std::mutex> lock(m_peerMutex);
            lastPruneMs = nowMs();
            pruneLocked(lastPruneMs);
            continue;
        }
        onDiscoveryPacket(std::string(buffer, (size_t)n), fromIp);
        /**
         * 每秒至少清一次超时对端：报文持续到达时根本走不到上面 n==0 那条支路，
         * 已经下线的设备会一直赖在列表里。
         */
        long long now = nowMs();
        if (now - lastPruneMs >= 1000) {
            std::lock_guard<std::mutex> lock(m_peerMutex);
            lastPruneMs = now;
            pruneLocked(now);
        }
    }
}

void LanMessenger::kcpLoop()
{
    // startServer() 阻塞于 runLoop()，需为其分配独立线程。
    // 它只该在 stop() 之后退出；真自己返回了（socket 出错）而线程就此结束时，
    // 界面还显示"监听中"却一条消息都收不到，且不重启回不来，所以补一层重启。
    bool started = false;
    while (m_running.load()) {
        if (started) {
            usleep(500 * 1000);
            if (!m_running.load()) {
                break;
            }
            if (m_kcp.init(m_port, false) < 0) {
                LOGW("LAN kcp restart: init %d fail, retry\n", m_port);
                continue;
            }
        }
        started = true;
        m_kcp.startServer();
        if (m_running.load()) {
            LOGW("LAN kcp loop returned while running: restarting\n");
            m_kcp.destroy();
        }
    }
    LOGI("LAN kcp loop exit\n");
}

void LanMessenger::touchPeer(const std::string& ip, int port, const std::string& name)
{
    // A peer that is missing from the discovery table (it may not be announcing) is recorded
    // too, so a reply can go straight back
    std::lock_guard<std::mutex> lock(m_peerMutex);
    std::string key = ip + ":" + std::to_string(port);
    auto it = m_peers.find(key);
    if (it == m_peers.end()) {
        LanPeer peer;
        peer.ip = ip;
        peer.port = port;
        peer.name = name;
        peer.lastSeenMs = nowMs();
        m_peers[key] = peer;
        pushEvent(LAN_PEER, "ADD|" + ip + "|" + std::to_string(port) + "|" + name);
    } else {
        it->second.lastSeenMs = nowMs();
        it->second.name = name;
    }
}

void LanMessenger::queueAck(const std::string& ip, int port, unsigned long long id)
{
    std::lock_guard<std::mutex> lock(m_outMutex);
    LanOutgoing ack;
    ack.ip = ip;
    ack.port = port;
    ack.id = id;
    ack.type = 'A';
    m_outbox.push_back(ack);
    m_outCv.notify_one();
}

void LanMessenger::onKcpMessage(const char* data, int len, const char* peerIp, int peerPort,
                                void* user)
{
    if (user == nullptr || data == nullptr || len <= 0) {
        return;
    }
    auto* self = static_cast<LanMessenger*>(user);
    // KCP 交回来的就是对方 ikcp_send() 的字节段，按 len 取即可
    self->handleWire(std::string(data, (size_t)len),
                     (peerIp != nullptr) ? std::string(peerIp) : std::string(), peerPort);
}

void LanMessenger::handleWire(const std::string& text, const std::string& peerIp, int peerPort)
{
    if (peerIp.empty()) {
        return;
    }
    // peerIp/peerPort 只是兜底：对端监听端口以报文里写的那个为准

    std::string magic = std::string(kMsgMagic) + "|";
    if (text.size() > magic.size() && text.compare(0, magic.size(), magic) == 0) {
        // D2DMSG | type | id | name | ip | port | selfId | body（selfId 可选，见 kMsgMagic）
        std::vector<std::string> parts = split(text, '|', 8);
        if (parts.size() < 7) {
            return;
        }
        char type = parts[1].empty() ? 'C' : parts[1][0];
        unsigned long long id = ::strtoull(parts[2].c_str(), nullptr, 10);
        const std::string& name = parts[3];
        const std::string& fromIp = parts[4];
        int fromPort = ::atoi(parts[5].c_str());
        // 8 段说明带了 selfId，正文在最后；7 段没带，正文就在 parts[6]
        const std::string& selfId = parts.size() >= 8 ? parts[6] : kEmpty;
        const std::string& body = parts.size() >= 8 ? parts[7] : parts[6];
        if (fromIp.empty()) {
            return;
        }
        // A peer that does not report a port is assumed to listen where we do
        if (fromPort <= 0) {
            fromPort = (peerPort > 0) ? peerPort : m_port;
        }

        if (type == 'A') {
            bool wasWaiting = false;
            {
                std::lock_guard<std::mutex> lock(m_pendingMutex);
                wasWaiting = m_pending.erase(id) > 0;
            }
            if (wasWaiting) {
                pushEvent(LAN_MESSAGE, "DELIVERED|" + fromIp + "|" + std::to_string(id));
            }
            touchPeer(fromIp, fromPort, name);
            return;
        }

        onMessageComplete(type, id, name, fromIp, fromPort, body, selfId);
        return;
    }

    std::string fragMagic = std::string(kFragMagic) + "|";
    if (text.size() > fragMagic.size() && text.compare(0, fragMagic.size(), fragMagic) == 0) {
        // D2DFRG | type | id | seq | total | name | ip | port | selfId | body
        std::vector<std::string> parts = split(text, '|', 10);
        if (parts.size() < 9) {
            return;
        }
        char type = parts[1].empty() ? 'C' : parts[1][0];
        unsigned long long id = ::strtoull(parts[2].c_str(), nullptr, 10);
        int seq = ::atoi(parts[3].c_str());
        int total = ::atoi(parts[4].c_str());
        const std::string& name = parts[5];
        const std::string& fromIp = parts[6];
        int fromPort = ::atoi(parts[7].c_str());
        const std::string& selfId = parts.size() >= 10 ? parts[8] : kEmpty;
        const std::string& body = parts.size() >= 10 ? parts[9] : parts[8];
        // seq/total are checked before anything is stored: a bogus total would otherwise make
        // us allocate an arbitrary number of slots
        if (fromIp.empty() || total <= 0 || total > LAN_MAX_FRAGS || seq < 0 || seq >= total) {
            return;
        }
        if (fromPort <= 0) {
            fromPort = (peerPort > 0) ? peerPort : m_port;
        }
        assembleFragment(type, id, seq, total, name, fromIp, fromPort, body, selfId);
        return;
    }

    // Legacy layout: sender name | sender IP | content. No id, so nothing to acknowledge.
    std::vector<std::string> parts = split(text, '|', 3);
    if (parts.size() < 3) {
        return;
    }
    const std::string& name = parts[0];
    const std::string& fromIp = parts[1];
    const std::string& body = parts[2];
    if (fromIp.empty()) {
        return;
    }
    touchPeer(fromIp, m_port, name);
    pushEvent(LAN_MESSAGE, "RECV|" + fromIp + "|" + name + "|" + body);
    LOGI("LAN recv (legacy) from %s (%s): %s\n", fromIp.c_str(), name.c_str(), body.c_str());
}

void LanMessenger::assembleFragment(char type, unsigned long long id, int seq, int total,
                                    const std::string& name, const std::string& ip, int port,
                                    const std::string& body, const std::string& selfId)
{
    const std::string key = ip + ":" + std::to_string(id);
    bool complete = false;
    std::string whole;
    char wholeType = type;
    std::string wholeName = name;
    std::string wholeIp = ip;
    int wholePort = port;
    std::string wholeSelfId = selfId;
    {
        std::lock_guard<std::mutex> lock(m_pendingMutex);
        long long now = nowMs();
        pruneFragmentsLocked(now);
        auto it = m_frags.find(key);
        if (it == m_frags.end()) {
            LanFragment frag;
            frag.type = type;
            frag.name = name;
            frag.ip = ip;
            frag.port = port;
            frag.selfId = selfId;
            frag.total = total;
            frag.parts.assign((size_t)total, std::string());
            frag.have = 0;
            frag.updatedMs = now;
            it = m_frags.insert(std::make_pair(key, frag)).first;
        }
        LanFragment& frag = it->second;
        frag.updatedMs = now;
        // 同一片可能来两次（对端重发了整条消息），第二次直接丢，否则 have 多数一次永远凑不齐
        if (seq < (int)frag.parts.size() && frag.parts[(size_t)seq].empty()) {
            frag.parts[(size_t)seq] = body;
            frag.have++;
        }
        if (frag.have >= frag.total) {
            complete = true;
            for (size_t i = 0; i < frag.parts.size(); ++i) {
                whole += frag.parts[i];
            }
            wholeType = frag.type;
            wholeName = frag.name;
            wholeIp = frag.ip;
            wholePort = frag.port;
            wholeSelfId = frag.selfId;
            m_frags.erase(it);
        }
    }
    if (complete) {
        onMessageComplete(wholeType, id, wholeName, wholeIp, wholePort, whole, wholeSelfId);
    }
}

void LanMessenger::pruneFragmentsLocked(long long nowMs)
{
    for (auto it = m_frags.begin(); it != m_frags.end();) {
        if (nowMs - it->second.updatedMs > LAN_FRAG_TIMEOUT_MS) {
            LOGW("LAN fragment %s expired: %d/%d pieces\n", it->first.c_str(),
                 it->second.have, it->second.total);
            it = m_frags.erase(it);
        } else {
            ++it;
        }
    }
}

void LanMessenger::onMessageComplete(char type, unsigned long long id, const std::string& name,
                                     const std::string& fromIp, int fromPort,
                                     const std::string& body, const std::string& selfId)
{
    bool fresh = false;
    {
        std::lock_guard<std::mutex> lock(m_pendingMutex);
        // 带了 selfId 就按它判重：发送方的 app 进程重启后 id 又从 1 数起，
        // 只用 ip 判会把重启后的新消息当成旧的悄悄丢掉
        fresh = markSeenLocked(selfId.empty() ? fromIp : selfId, id);
    }
    touchPeer(fromIp, fromPort, name);
    /**
     * 回执照发：即使该消息是重发（ACK 丢了导致对端又发了一遍），也要再确认一次，
     * 否则对端会一直重发到次数用尽，最终向用户报告「未送达」—— 而数据实际早已收到。
     */
    queueAck(fromIp, fromPort, id);
    if (fresh) {
        const char* event = (type == 'I') ? "RECVIMG" : "RECV";
        pushEvent(LAN_MESSAGE, std::string(event) + "|" + fromIp + "|" + name + "|" + body);
        LOGI("LAN recv %s from %s (%s), %zu bytes\n", (type == 'I') ? "image" : "text",
             fromIp.c_str(), name.c_str(), body.size());
    }
}

std::string LanMessenger::buildPayload(char type, unsigned long long id,
                                       const std::string& body) const
{
    std::string payload = std::string(kMsgMagic) + "|" + type + "|"
                          + std::to_string(id) + "|" + m_name + "|" + m_selfIp + "|"
                          + std::to_string(m_port) + "|" + m_selfId + "|" + body;
    return payload;
}

std::string LanMessenger::buildFragment(char type, unsigned long long id, int seq, int total,
                                        const std::string& chunk) const
{
    return std::string(kFragMagic) + "|" + type + "|" + std::to_string(id) + "|"
           + std::to_string(seq) + "|" + std::to_string(total) + "|" + m_name + "|"
           + m_selfIp + "|" + std::to_string(m_port) + "|" + m_selfId + "|" + chunk;
}

bool LanMessenger::sendOnce(const std::string& ip, int port, char type,
                               unsigned long long id, const std::string& body)
{
    std::string payload = buildPayload(type, id, body);
    if ((int)payload.size() <= LAN_CHUNK_BODY) {
        // One KCP message is enough: sendTo() picks the peer's ikcp session, creating it if
        // this is the first thing we ever said to that address.
        return m_kcp.sendTo(ip.c_str(), port, payload.data(), (int)payload.size()) >= 0;
    }

    /**
     * 太长（只有图片会走到这儿）：ikcp_send() 一次最多放 (IKCP_WND_RCV - 1) * mss ≈ 170KB，
     * 256KB 的 base64 不切时 ikcp_send 直接返回 -2，图片永远发不出去。
     * 切的是正文不是整包：每片自带一份头，对端按 seq 拼回去就是完整 body。
     * 分片的可靠性交给 KCP，应用层只看整条有没有被 ACK。
     */
    int total = (int)((body.size() + LAN_CHUNK_BODY - 1) / LAN_CHUNK_BODY);
    if (total < 1) {
        total = 1;
    }
    if (total > LAN_MAX_FRAGS) {
        LOGE("LAN send %s: %zu bytes needs %d pieces, over the cap\n", ip.c_str(),
             body.size(), total);
        return false;
    }
    bool ok = true;
    for (int seq = 0; seq < total; ++seq) {
        size_t off = (size_t)seq * (size_t)LAN_CHUNK_BODY;
        size_t len = body.size() - off;
        if (len > (size_t)LAN_CHUNK_BODY) {
            len = (size_t)LAN_CHUNK_BODY;
        }
        std::string piece = buildFragment(type, id, seq, total, body.substr(off, len));
        if (m_kcp.sendTo(ip.c_str(), port, piece.data(), (int)piece.size()) < 0) {
            ok = false;
        }
    }
    return ok;
}

unsigned long long LanMessenger::enqueue(char type, const std::string& ip, int port,
                                         const std::string& body)
{
    unsigned long long id = 0;
    {
        std::lock_guard<std::mutex> lock(m_pendingMutex);
        id = m_nextId++;
    }
    {
        std::lock_guard<std::mutex> lock(m_outMutex);
        LanOutgoing out;
        out.ip = ip;
        out.port = port;
        out.body = body;
        out.id = id;
        out.type = type;
        m_outbox.push_back(out);
    }
    m_outCv.notify_one();
    return id;
}

int LanMessenger::sendText(const std::string& ip, int port, const std::string& text)
{
    if (ip.empty() || port <= 0) {
        return -1;
    }
    std::string body = sanitize(text);
    if (body.empty()) {
        return -1;
    }
    unsigned long long id = enqueue('C', ip, port, body);
    // The bubble appears right away in "sending" state; DELIVERED / UNDELIVERED follow.
    pushEvent(LAN_MESSAGE,
              "SENDING|" + ip + "|" + std::to_string(id) + "|" + body);
    return 0;
}

int LanMessenger::sendImage(const std::string& ip, int port, const std::string& base64)
{
    if (ip.empty() || port <= 0) {
        return -1;
    }
    if (base64.empty()) {
        return -1;
    }
    if ((int)base64.size() > LAN_MAX_IMAGE_CHARS) {
        return -4;
    }
    // base64 字符集里没有分隔符和换行，按字符集校验一遍就够了
    if (!isBase64(base64)) {
        return -3;
    }
    unsigned long long id = enqueue('I', ip, port, base64);
    // The image itself is not carried by the event: the Java side already has the bitmap and
    // drew the bubble, so the event only needs to bring the id back for the delivery state
    pushEvent(LAN_MESSAGE, "SENDINGIMG|" + ip + "|" + std::to_string(id));
    return 0;
}

bool LanMessenger::markSeenLocked(const std::string& sender, unsigned long long id)
{
    std::string key = sender + ":" + std::to_string(id);
    if (m_seenIds.count(key) > 0) {
        return false;   // 重发：ACK 丢了，对端又发了一遍
    }
    m_seenIds.insert(key);
    m_seenOrder.push_back(key);
    while ((int)m_seenOrder.size() > LAN_SEEN_CAP) {
        m_seenIds.erase(m_seenOrder.front());
        m_seenOrder.pop_front();
    }
    return true;
}

/**
 * 发一条：取快照 → 发 → 回写状态。整条在 sendLoop() 的线程里同步做完，不再另起线程。
 *
 * 串行是必须的：ikcp_send() 只把段挂到发送队列上，几微秒就返回，串行不会堵住后面的消息；
 * 而各起一条线程并发发时，ikcp_send() 的先后由线程调度决定，KCP 又只保证按调用顺序交付，
 * 同一条会话里应用层消息就会乱序（图文交替连发时实测相邻两条会颠倒）。
 */
bool LanMessenger::sendOnceById(unsigned long long id)
{
    LanPending snapshot;
    {
        std::lock_guard<std::mutex> lock(m_pendingMutex);
        auto it = m_pending.find(id);
        if (it == m_pending.end()) {
            return false;
        }
        snapshot = it->second;
    }
    bool ok = sendOnce(snapshot.ip, snapshot.port, snapshot.type, id, snapshot.body);
    long long now = nowMs();
    std::lock_guard<std::mutex> lock(m_pendingMutex);
    auto it = m_pending.find(id);
    if (it == m_pending.end()) {
        return ok;   // 发送期间 ACK 已经到了
    }
    it->second.inFlight = false;
    if (ok) {
        it->second.waitingAck = true;
        it->second.dueMs = now + LAN_ACK_TIMEOUT_MS;
    } else {
        it->second.waitingAck = false;
        it->second.dueMs = now + LAN_RETRY_DELAY_MS;
    }
    return ok;
}

std::vector<unsigned long long> LanMessenger::serviceDueLocked(long long nowMs)
{
    std::vector<unsigned long long> retry;
    for (auto it = m_pending.begin(); it != m_pending.end();) {
        LanPending& p = it->second;
        if (p.inFlight || nowMs < p.dueMs) {
            ++it;
            continue;
        }
        if (p.attempt >= LAN_SEND_MAX_ATTEMPTS) {
            if (p.waitingAck) {
                pushEvent(LAN_MESSAGE, "UNDELIVERED|" + p.ip + "|" + std::to_string(p.id)
                          + "|对方未确认");
            } else {
                pushEvent(LAN_MESSAGE, "SENDFAIL|" + p.ip + "|" + std::to_string(p.id)
                          + "|连不上对端");
            }
            it = m_pending.erase(it);
            continue;
        }
        p.attempt++;
        p.inFlight = true;
        retry.push_back(p.id);
        ++it;
    }
    return retry;
}

void LanMessenger::sendLoop()
{
    while (m_running.load()) {
        std::deque<LanOutgoing> batch;
        {
            std::unique_lock<std::mutex> lock(m_outMutex);
            // Wake up every 200ms even with nothing queued, so the pending list gets serviced
            m_outCv.wait_for(lock, std::chrono::milliseconds(200),
                             [this]() { return !m_outbox.empty() || !m_running.load(); });
            batch.swap(m_outbox);
        }
        long long now = nowMs();
        std::vector<unsigned long long> retry;
        std::vector<LanOutgoing> acks;
        {
            std::lock_guard<std::mutex> lock(m_pendingMutex);
            for (const LanOutgoing& out : batch) {
                if (out.type == 'A') {
                    acks.push_back(out);
                    continue;
                }
                LanPending p;
                p.ip = out.ip;
                p.port = out.port;
                p.body = out.body;
                p.id = out.id;
                p.type = out.type;
                // Send straight away: attempt is bumped and inFlight set by serviceDueLocked
                p.attempt = 0;
                p.dueMs = now;
                m_pending[p.id] = p;
            }
            retry = serviceDueLocked(now);
        }
        // 锁外按入队顺序发：ACK 先走，再按 id 升序发数据消息（m_pending 是 map，
        // serviceDueLocked 遍历出来的天然有序）。顺序确定，对端收到的就是这里的顺序。
        for (const LanOutgoing& ack : acks) {
            sendOnce(ack.ip, ack.port, 'A', ack.id, std::string());
        }
        for (unsigned long long id : retry) {
            sendOnceById(id);
        }
    }
    LOGI("LAN send loop exit\n");
}

void LanMessenger::refresh()
{
    if (!m_running.load()) {
        return;
    }
    announceOnce();
    std::lock_guard<std::mutex> lock(m_peerMutex);
    pushEvent(LAN_PEER, "CLEAR");
    pushPeersLocked();
}
