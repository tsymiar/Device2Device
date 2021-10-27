#pragma once
#include <cstring>
#include <string>
#include <vector>
#include <deque>
#include <memory>
#include <mutex>
#include <functional>

enum KaiRoles {
    NONE = 0,
    PRODUCER,
    CONSUMER,
    SERVER,
    BROKER,
    CLIENT,
    PUBLISH,
    SUBSCRIBE
};

using SOCKET = int;

class KaiSocket {
public:
    struct Header {
        char rsv;
        int etag;
        volatile unsigned long long ssid; //ssid = port | socket | ip
        char topic[32];
        unsigned int size;
    } __attribute__((packed));
    struct Message {
        Header head{};
        struct Payload {
            char stat[8];
            char body[0];
        } __attribute__((packed)) data {};
        void* operator new(size_t, const Message& msg) {
            static void* mss = (void*)(&msg);
            return mss;
        }
    } __attribute__((packed));
    typedef int(*KAISOCKHOOK)(KaiSocket*);
    typedef void(*RECVCALLBACK)(const Message&);
    static char G_KaiRole[][0xa];
    KaiSocket() = default;
    virtual ~KaiSocket() = default;
public:
    int Initialize(unsigned short lstnprt);
    int Initialize(const char* srvip, unsigned short srvport);
    // workflow
    int start();
    int connect();
    //
    int Broker();
    ssize_t Publisher(const std::string& topic, const std::string& payload, ...);
    ssize_t Subscriber(const std::string& message, RECVCALLBACK callback = nullptr);
    static void wait(unsigned int tms);
};
