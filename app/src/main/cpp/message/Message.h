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
    LOG_VIEW,
    UDP_SERVER,
    UDP_CLIENT,
    SUBSCRIBER,
    PUBLISHER,
    UPDATE_VIEW
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
