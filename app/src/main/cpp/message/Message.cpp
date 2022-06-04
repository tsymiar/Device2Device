//
// Created by Shenyrion on 2022/4/28.
//

#include "Message.h"

std::queue <Messaging> Message::m_msgQue;

Messaging Message::getMessage()
{
    std::lock_guard<std::mutex> lock(m_mtx);
    Messaging messaging{};
    if (m_msgQue.empty())
        return messaging;
    messaging = m_msgQue.front();
    if (!messaging.message.empty()) {
        m_msgQue.pop();
    }
    return messaging;
}

Message& Message::instance()
{
    static Message message;
    return message;
}

void Message::setMessage(const std::string& message, MASSAGER massager)
{
    std::lock_guard<std::mutex> lock(m_mtx);
    Messaging messaging;
    messaging.massager = massager;
    messaging.message = message;
    m_msgQue.emplace(messaging);
}
