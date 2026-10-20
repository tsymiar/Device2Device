#pragma once
#include <atomic>
#include <condition_variable>
#include <deque>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "UdpSocket.h"
#include "KcpSocket.h"

/** UDP broadcast port for discovery: one process per machine on the LAN, and a fixed port is
    what lets them find each other */
#define LAN_DISCOVERY_PORT 39000
/** Default text-message port (the caller may override it): both the UDP port this machine's
    KCP socket binds and the target port used on the peer */
#define LAN_CHAT_PORT 39001
/**
 * 多久没收到某台设备的广播就判它离线。
 *
 * 广播间隔 1 秒，但手机屏幕熄灭后 WiFi 会进省电，广播帧可能被成片过滤掉，
 * 而这台设备其实还在线（回到前台立刻就能收发）。判得太紧（十几秒）表现就是
 * "过一会儿设备全掉了"，所以给到 45 秒：真要下线的设备 45 秒内也会消失，
 * 在线的设备不会因为一次省电静默被误摘。
 */
#define LAN_PEER_TIMEOUT_MS 45000
/**
 * How many times one message is sent before it counts as undelivered.
 *
 * KCP already retransmits packets that were lost, so a failure here usually means the peer
 * itself is gone (app closed, WiFi dropped). Three tries spread over a few seconds is what
 * makes "send" feel reliable on a LAN.
 */
#define LAN_SEND_MAX_ATTEMPTS 3
/** How long to wait for the peer's ACK before sending the same message again */
#define LAN_ACK_TIMEOUT_MS 3000
/** Pause between two attempts when the send itself failed */
#define LAN_RETRY_DELAY_MS 1500
/**
 * Ceiling for a base64 image: 256KB of base64 is about 192KB of JPEG, a 1024px photo at a
 * usable quality. The Java side (IMAGE_MAX_CHARS) has to stay in step with this.
 */
#define LAN_MAX_IMAGE_CHARS (256 * 1024)
/**
 * Largest body one KCP message carries; anything bigger is split into pieces.
 *
 * ikcp_send() refuses a message that would need (IKCP_WND_RCV - 1) = 127 segments or more,
 * i.e. about 170KB at the default MTU, so 256KB of base64 has to be chopped. 64KB is ~48
 * segments — comfortably under the ceiling, and it keeps UDP fragmentation where KCP was
 * tuned for it.
 */
#define LAN_CHUNK_BODY (64 * 1024)
/** Sanity cap on how many pieces one message may be split into */
#define LAN_MAX_FRAGS 64
/** How long a half-reassembled message is kept before it is thrown away */
#define LAN_FRAG_TIMEOUT_MS 30000

struct LanPeer {
    std::string ip;
    int port = 0;
    std::string name;
    long long lastSeenMs = 0;
};

/** One message waiting to go out */
struct LanOutgoing {
    std::string ip;
    int port = 0;
    std::string body;
    unsigned long long id = 0;
    /** 'C' = chat (needs an ACK back), 'A' = ACK (acking an ACK would never settle) */
    char type = 'C';
};

/**
 * A message that has been sent and is waiting for the peer's ACK, or one whose send failed
 * and is waiting to be tried again.
 */
struct LanPending {
    std::string ip;
    int port = 0;
    std::string body;
    unsigned long long id = 0;
    /** 'C' chat or 'I' image: both are resent the same way, only the display differs */
    char type = 'C';
    int attempt = 0;
    /** when to act next: send again, or give up */
    long long dueMs = 0;
    /** true = sent and waiting for ACK, false = last send failed */
    bool waitingAck = false;
    /** true while a send for this message is running, so it is not scheduled twice */
    bool inFlight = false;
};

/** One message being put back together from its pieces; key = "ip:id" */
struct LanFragment {
    /** 'C' chat or 'I' image */
    char type = 'C';
    std::string name;
    std::string ip;
    int port = 0;
    /** 发送方这一轮 start() 的会话标识：拼齐后原样交给去重，见 kMsgMagic */
    std::string selfId;
    int total = 0;
    /** empty slot = that piece has not arrived yet */
    std::vector<std::string> parts;
    int have = 0;
    long long updatedMs = 0;
};

/**
 * LAN text messaging: discovery plus send/receive. One instance is both client and server.
 *
 * Discovery: periodic UDP broadcast (to 255.255.255.255 and to the broadcast address of this
 *            machine's /24). Hearing someone else announce adds it to the peer table, and a
 *            peer that stops announcing is dropped on timeout. Table changes go back to the
 *            caller as LAN_PEER events.
 * Messaging: KCP over UDP, one socket playing both roles.
 *            The socket binds 0.0.0.0:port, so any machine on the LAN can reach it, and it
 *            keeps one ikcp session per peer address — a session is created on demand, so
 *            sending is just "sendTo(ip, port)" and receiving is a KCP callback. No broker,
 *            no subscriber, no per-message connection.
 *
 * Events go into the Message queue and are drained by the polling thread that already exists
 * on the Java side, so nothing here depends on a JNIEnv.
 */
class LanMessenger {
public:
    static LanMessenger& instance();

    /**
     * @param name   name this device shows on the LAN
     * @param selfIp this machine's LAN IP, computed on the Java side: used to filter out our
     *               own broadcasts, to derive the subnet broadcast address, and to ship along
     *               with every message so the peer can reply directly
     * @param port   text-message port (the UDP port this machine's KCP socket binds)
     */
    int start(const std::string& name, const std::string& selfIp, int port);
    void stop();
    bool isRunning() const;
    /**
     * 发现广播用的 UDP 端口，默认 LAN_DISCOVERY_PORT。
     * 同机跑两个实例（自测、或 39000 被别的程序占了）时需先修改此处再 start()，以免后启动的
     * 那个因端口被占而失败。只影响本机：发现靠双方用同一个端口喊话，对端仍须用默认值。
     */
    void setDiscoveryPort(int port);
    /** Announce once more right away and re-push the whole peer table (the Java side clears
        first, then adds entries one by one) */
    void refresh();
    /** Send one text message to a peer: queued here, handed to KCP by sendLoop() */
    int sendText(const std::string& ip, int port, const std::string& text);
    /** Send one image, already encoded to base64 by the caller */
    int sendImage(const std::string& ip, int port, const std::string& base64);

private:
    LanMessenger();
    ~LanMessenger();
    LanMessenger(const LanMessenger&) = delete;
    LanMessenger& operator=(const LanMessenger&) = delete;

    void announceLoop();
    void discoveryLoop();
    /**
     * Drains the outbox and services the pending list.
     *
     * Sending lives here rather than in sendText(): a big image is split into pieces and
     * each one goes through ikcp_send(), so keeping it off the caller's thread keeps the UI
     * smooth however large the message is.
     */
    void sendLoop();
    /** Runs KcpSocket's loop (update -> recvfrom -> ikcp_input -> ikcp_recv); it blocks, so
        it needs its own thread, and it is restarted if it ever comes back on its own */
    void kcpLoop();
    /** Where messages received over KCP land; called on the KCP thread, so it must not touch
        a JNIEnv */
    static void onKcpMessage(const char* data, int len, const char* peerIp, int peerPort,
        void* user);
    /** Handles one decoded wire payload: D2DMSG (a whole message) or D2DFRG (one piece) */
    void handleWire(const std::string& payload, const std::string& fromIp, int fromPort);
    /** Stores one piece and, when the last one lands, hands the whole message on */
    void assembleFragment(char type, unsigned long long id, int seq, int total,
                          const std::string& name, const std::string& ip, int port,
                          const std::string& body, const std::string& selfId);
    /** Drops half-reassembled messages nobody ever finished (caller holds m_pendingMutex) */
    void pruneFragmentsLocked(long long nowMs);
    /** Common tail of the two receive paths: dedupe, remember the peer, ACK, report */
    void onMessageComplete(char type, unsigned long long id, const std::string& name,
                           const std::string& fromIp, int fromPort, const std::string& body,
                           const std::string& selfId);

    void announceOnce();
    /** 单播一封"回应"给指定 IP：对方喊了话而我们不认识它，省得它等下一轮广播 */
    void replyOnce(const std::string& ip);
    /** Parses one announcement or reply packet; false if it came from us or is malformed */
    bool onDiscoveryPacket(const std::string& payload, const std::string& fromIp);
    void pruneLocked(long long nowMs);
    void pushPeersLocked();
    void pushEvent(int type, const std::string& text);

    /** Builds the wire payload: D2DMSG|type|id|name|ip|port|selfId|body */
    std::string buildPayload(char type, unsigned long long id, const std::string& body) const;
    /** Builds one piece of a split message:
        D2DFRG|type|id|seq|total|name|ip|port|selfId|chunk */
    std::string buildFragment(char type, unsigned long long id, int seq, int total,
                             const std::string& chunk) const;
    /** Sends one message, splitting the body when it is too big for a single ikcp_send() */
    bool sendOnce(const std::string& ip, int port, char type, unsigned long long id,
        const std::string& body);
    /** Marks due entries for another attempt or gives up on them; returns ids to send */
    std::vector<unsigned long long> serviceDueLocked(long long nowMs);

    /**
     * 发一条（在 sendLoop() 的线程里同步做完）：取快照 → 发 → 回写状态。
     * 串行才能保证对端收到的顺序与入队顺序一致，见实现里的说明。
     */
    bool sendOnceById(unsigned long long id);
    /** Remembers a received id so a resent message is not displayed twice */
    bool markSeenLocked(const std::string& sender, unsigned long long id);
    /** Records or refreshes a peer, adding it to the table when it is new */
    void touchPeer(const std::string& ip, int port, const std::string& name);
    /** Queues an ACK for a chat message we just received */
    void queueAck(const std::string& ip, int port, unsigned long long id);
    /** Assigns the next id and queues the message; returns the new id */
    unsigned long long enqueue(char type, const std::string& ip, int port,
        const std::string& body);

    static long long nowMs();
    /** Delimiters and newlines cannot appear in a packet, so they are replaced */
    static std::string sanitize(const std::string& in);

    std::atomic<bool> m_running;
    std::string m_name;
    std::string m_selfIp;
    std::string m_selfId;
    int m_port{ LAN_CHAT_PORT };
    int m_discoveryPort{ LAN_DISCOVERY_PORT };

    /** UDP socket used for discovery: announcing and receiving, reusing UdpSocket */
    UdpSocket m_discovery;

    std::thread* m_announceThread{ nullptr };
    std::thread* m_discoveryThread{ nullptr };
    std::thread* m_sendThread{ nullptr };
    std::thread* m_kcpThread{ nullptr };
    /**
     * The one KCP socket: bound to 0.0.0.0:m_port, one ikcp session per peer address.
     * Held by value — it is created once in start() and torn down in stop().
     */
    KcpSocket m_kcp;

    mutable std::mutex m_peerMutex;
    /** key = "ip:port" */
    std::map<std::string, LanPeer> m_peers;

    /** Outbox: sendText() only appends here, sendLoop() does the blocking work */
    std::mutex m_outMutex;
    std::condition_variable m_outCv;
    std::deque<LanOutgoing> m_outbox;

    /** Guards everything below: messages waiting for an ACK, and ids already displayed */
    std::mutex m_pendingMutex;
    /** key = message id */
    std::map<unsigned long long, LanPending> m_pending;
    /** "ip:id" of chat messages already shown, capped at LAN_SEEN_CAP */
    std::set<std::string> m_seenIds;
    std::deque<std::string> m_seenOrder;
    /** "ip:id" of messages still being reassembled from pieces */
    std::map<std::string, LanFragment> m_frags;
    unsigned long long m_nextId{ 1 };
};
