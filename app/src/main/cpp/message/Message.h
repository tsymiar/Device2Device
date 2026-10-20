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
    /** 局域网文本消息：发现到的对端（CLEAR / ADD|ip|port|name / DEL|...） */
    LAN_PEER,
    /** 局域网文本消息：收到的内容 / 发送结果（RECV / SENT / SENDFAIL / LISTEN） */
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
