//
// Created by Shenyrion on 2022/4/28.
//

#include "Message.h"

std::queue <Messaging> Message::m_msgQue;
std::mutex Message::m_mtx;

namespace {

const size_t MAX_QUEUED = 512;

bool contByte(unsigned char c)
{
    return (c & 0xC0) == 0x80;
}

void toUtf8Safe(std::string& text)
{
    const unsigned char* p = reinterpret_cast<const unsigned char*>(text.data());
    const size_t n = text.size();
    std::string out;
    out.reserve(n);
    size_t i = 0;
    while (i < n) {
        const unsigned char c = p[i];
        if (c == 0x00) {
            out.push_back('?');
            ++i;
            continue;
        }
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
            ++i;
            continue;
        }
        int extra = 0;
        unsigned int cp = 0;
        if ((c & 0xE0) == 0xC0) {
            extra = 1;
            cp = c & 0x1Fu;
        } else if ((c & 0xF0) == 0xE0) {
            extra = 2;
            cp = c & 0x0Fu;
        } else if ((c & 0xF8) == 0xF0) {
            extra = 3;
            cp = c & 0x07u;
        } else {
            out.push_back('?');
            ++i;
            continue;
        }
        if (i + static_cast<size_t>(extra) >= n) {
            out.push_back('?');
            ++i;
            continue;
        }
        bool ok = true;
        for (int k = 1; k <= extra; ++k) {
            const unsigned char cc = p[i + static_cast<size_t>(k)];
            if (!contByte(cc)) {
                ok = false;
                break;
            }
            cp = (cp << 6) | (cc & 0x3Fu);
        }
        if (!ok || cp > 0x10FFFFu || (cp >= 0xD800u && cp <= 0xDFFFu)) {
            out.push_back('?');
            ++i;
            continue;
        }
        out.append(text, i, static_cast<size_t>(extra) + 1);
        i += static_cast<size_t>(extra) + 1;
    }
    text.swap(out);
}

}

Messaging Message::getMessage()
{
    std::lock_guard<std::mutex> lock(m_mtx);
    Messaging messaging{};
    if (m_msgQue.empty())
        return messaging;
    messaging = m_msgQue.front();
    // 无条件出队：空串由调用方（JNI getMessage）过滤，出队操作不可交由该条件判断，
    // 否则一条空消息会永远占着队首，后面所有事件全被堵死
    m_msgQue.pop();
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
    toUtf8Safe(messaging.message);
    while (m_msgQue.size() >= MAX_QUEUED) {
        m_msgQue.pop();
    }
    m_msgQue.emplace(std::move(messaging));
}
