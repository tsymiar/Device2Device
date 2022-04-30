//
// Created by Shenyrion on 2022/4/28.
//

#ifndef DEVIDROID_MESSAGE_H
#define DEVIDROID_MESSAGE_H

#include <string>
#include <queue>

enum MASSAGER {
    MESSAGE,
    TOAST,
    UDP_SERVER,
    UDP_CLIENT,
    SUBSCRIBER,
    PUBLISHER
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
};


#endif //DEVIDROID_MESSAGE_H
