#ifndef DEVICE2DEVICE_NetworkClientTest_H
#define DEVICE2DEVICE_NetworkClientTest_H

#include <string>

class NetworkClientTest {
public:
    static int runTcpTest(const std::string& ip, unsigned short port, const std::string& message);
    static int runUdpTest(const std::string& ip, unsigned short port, const std::string& message);
    static int runKcpTest(const std::string& ip, unsigned short port);
};

#endif
