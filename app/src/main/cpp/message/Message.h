//
// Created by Shenyrion on 2022/4/28.
//

#ifndef DEVICE2DEVICE_MESSAGE_H
#define DEVICE2DEVICE_MESSAGE_H

#include <string>
#include <queue>
#include <mutex>

enum MASSAGER {
    MESSAGE,
    TOAST,
    TEXTURE,
    SUBSCRIBER,
    PUBLISHER,
    KCP_VIEW,
    FILE_PROGRESS,
    MSG_HINT,
    /** UDP 服务端的消息：启动时处理一次状态（端口），之后处理每一包收到的数据 */
    UDP_SERVER,
    UDP_CLIENT,
    /** KCP 服务端收到的消息：Java 侧打到页面底部 hint 区 */
    KCP_HINT,
    /** KCP 客户端的消息（启动 / 发送 / 收到的回射包）：Java 侧打到页面 status 区 */
    KCP_CLIENT
};

struct Messaging {
    MASSAGER massager = MESSAGE;
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
    std::mutex m_mtx = {};
};

#endif //DEVICE2DEVICE_MESSAGE_H
