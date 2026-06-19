#ifndef DEVICE2DEVICE_NetworkServerTest_H
#define DEVICE2DEVICE_NetworkServerTest_H

#include <string>

class NetworkServerTest {
public:
    /** TCP echo server — accepts one connection, echoes back, then exits */
    static int runTcpServer(unsigned short port);

    /** UDP echo server — receives one datagram, echoes back to sender, then exits */
    static int runUdpServer(unsigned short port);

    /** KCP server — runs server loop; press Enter or wait for timeout to stop */
    static int runKcpServer(unsigned short port);
};

#endif
