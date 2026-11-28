//
// Created by Shenyrion on 2022/4/28.
//

#ifndef DEVICE2DEVICE_MESSAGE_H
#define DEVICE2DEVICE_MESSAGE_H

#include <string>
#include <queue>
#include <mutex>

enum MASSAGER {
    MSG_STAT,
    TOAST,
    TEXTURE,
    SUBSCRIBER,
    PUBLISHER,
    FILE_PROGRESS,
    MSG_HINT,
    /** UDP 服务端的消息：启动时处理一次状态（端口），之后逐包处理收到的数据 */
    UDP_SERVER,
    UDP_CLIENT,
    /** KCP 服务端收到的消息：Java 侧打到页面底部 hint 区 */
    KCP_VIEW,
    KCP_HINT,
    /** KCP 客户端的消息（启动 / 发送 / 收到的回射包）：Java 侧打到页面 status 区 */
    KCP_CLIENT,
    /** 文件传输发现到的对端快照："ip:port;ip:port"，空串表示一台都没有 */
    FILE_PEER,
    /**
     * LAN chat events, 3-char verb: 1st char = type (P=peer, M=message), last 2 = function code.
     * 值仅在本 app 内使用（JNI 按枚举名传值，不落盘、不跨进程），与 Mac 不必一致；
     * 新类型一律追加到末尾，避免既有值移位。
     */
    LAN_PEER,
    LAN_MESSAGE
};

struct Messaging {
    MASSAGER massager = MSG_STAT;
    std::string message;
};

class Message {
public:
    static Message& instance();

    Messaging getMessage();

    void setMessage(const std::string &message, MASSAGER massager);

private:
    Message() {};

    ~Message() {};
    static std::queue <Messaging> m_msgQue;
    static std::mutex m_mtx;
};

#endif //DEVICE2DEVICE_MESSAGE_H
