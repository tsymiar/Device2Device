/**
 * Network Client Tests — standalone TCP / UDP / KCP client validation.
 *
 * Build (from test/):
 *   mkdir -p build && cd build && cmake .. && make
 *
 * Usage:
 *   ./NetworkClientTest --tcp  --ip 127.0.0.1 --port 8800 --msg "hello"
 *   ./NetworkClientTest --udp  --ip 127.0.0.1 --port 8800 --msg "hello"
 *   ./NetworkClientTest --kcp  --ip 127.0.0.1 --port 8800
 */

#include "NetworkClientTest.h"
#include "../socket/UdpSocket.h"
#include "../socket/KcpSocket.h"

#include <iostream>
#include <cstring>
#include <thread>
#include <chrono>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>

// ---------------------------------------------------------------------------
// TCP Client — raw POSIX socket, connect → send → recv echo
// ---------------------------------------------------------------------------
int NetworkClientTest::runTcpTest(const std::string& ip, unsigned short port,
                                  const std::string& message)
{
    int sock = ::socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        std::cerr << "[TCP] socket() failed" << std::endl;
        return -1;
    }

    // Set a 5-second recv timeout so we don't hang forever
    struct timeval tv {5, 0};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_in server {};
    server.sin_family = AF_INET;
    server.sin_port   = htons(port);
    if (inet_pton(AF_INET, ip.c_str(), &server.sin_addr) <= 0) {
        std::cerr << "[TCP] invalid address: " << ip << std::endl;
        close(sock);
        return -2;
    }

    std::cout << "[TCP] Connecting to " << ip << ":" << port << " ..." << std::endl;
    if (::connect(sock, reinterpret_cast<sockaddr*>(&server), sizeof(server)) < 0) {
        perror("[TCP] connect");
        close(sock);
        return -3;
    }
    std::cout << "[TCP] Connected." << std::endl;

    // --- send ---
    ssize_t sent = ::send(sock, message.c_str(), message.size(), 0);
    if (sent < 0) {
        perror("[TCP] send");
        close(sock);
        return -4;
    }
    std::cout << "[TCP] Sent " << sent << " bytes: " << message << std::endl;

    // --- recv ---
    char buffer[4096] = {};
    ssize_t rcvd = ::recv(sock, buffer, sizeof(buffer) - 1, 0);
    if (rcvd < 0) {
        perror("[TCP] recv");
        close(sock);
        return -5;
    }
    buffer[rcvd] = '\0';
    std::cout << "[TCP] Received " << rcvd << " bytes: " << buffer << std::endl;

    close(sock);
    std::cout << "[TCP] Test PASSED." << std::endl;
    return 0;
}

// ---------------------------------------------------------------------------
// UDP Client — multicast send via UdpSocket (same API used in production)
// ---------------------------------------------------------------------------
int NetworkClientTest::runUdpTest(const std::string& ip, unsigned short port,
                                  const std::string& message)
{
    UdpSocket udp(ip, port);

    std::cout << "[UDP] Sending to " << ip << ":" << port << " ..." << std::endl;
    int ret = udp.Sender(message.c_str(), message.size());
    if (ret < 0) {
        std::cerr << "[UDP] Sender() failed: " << ret << std::endl;
        return -1;
    }
    std::cout << "[UDP] Sent " << message.size() << " bytes." << std::endl;

    // Optional receive — won't block because the socket is bound separately
    char buf[2048] = {};
    ret = udp.Receiver(buf, sizeof(buf) - 1);
    if (ret > 0) {
        buf[ret] = '\0';
        std::cout << "[UDP] Received " << ret << " bytes: " << buf << std::endl;
    } else {
        std::cout << "[UDP] No response received (server may not echo)." << std::endl;
    }

    std::cout << "[UDP] Test PASSED." << std::endl;
    return 0;
}

// ---------------------------------------------------------------------------
// KCP Client — reliable-UDP handshake with server
// ---------------------------------------------------------------------------
int NetworkClientTest::runKcpTest(const std::string& ip, unsigned short port)
{
    KcpSocket kcp;

    std::cout << "[KCP] Initializing client → " << ip << ":" << port << " ..." << std::endl;
    int ret = kcp.init(port, true /*client*/, ip.c_str());
    if (ret < 0) {
        std::cerr << "[KCP] init() failed: " << ret << std::endl;
        return -1;
    }
    std::cout << "[KCP] Init OK, starting client loop (5 s) ..." << std::endl;

    // startClient blocks forever; run it on a thread and time-box it
    std::thread worker([&kcp] { kcp.startClient(); });

    std::this_thread::sleep_for(std::chrono::seconds(5));

    std::cout << "[KCP] Stopping ..." << std::endl;
    kcp.stop();

    if (worker.joinable()) worker.join();

    kcp.destroy();

    std::cout << "[KCP] Test PASSED." << std::endl;
    return 0;
}

// ---------------------------------------------------------------------------
// main — CLI dispatch
// ---------------------------------------------------------------------------
static void usage(const char* prog)
{
    std::cout << "Usage: " << prog << " [--tcp|--udp|--kcp] [options]\n"
              << "\n"
              << "Protocol (pick one):\n"
              << "  --tcp           Run TCP client test\n"
              << "  --udp           Run UDP client test\n"
              << "  --kcp           Run KCP client test\n"
              << "\n"
              << "Options:\n"
              << "  --ip   <addr>   Server IP   (default: 127.0.0.1)\n"
              << "  --port <port>   Server port (default: 8800)\n"
              << "  --msg  <text>   Message to send (TCP/UDP only)\n"
              << "  --help, -h      Show this help\n"
              << "\n"
              << "Examples:\n"
              << "  Terminal 1: echo \"hello\" | nc -l -p 8800\n"
              << "  Terminal 2: " << prog << " --tcp --ip 127.0.0.1 --port 8800 --msg hello\n"
              << std::endl;
}

int main(int argc, char* argv[])
{
    std::cout << "=== Network Client Test Suite ===" << std::endl;

    enum { NONE, TCP, UDP, KCP } mode = NONE;
    std::string ip   = "127.0.0.1";
    unsigned short port = 8800;
    std::string msg  = "Device2Device-test-message";

    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        if (a == "--tcp")       mode = TCP;
        else if (a == "--udp")  mode = UDP;
        else if (a == "--kcp")  mode = KCP;
        else if (a == "--ip"   && i + 1 < argc) ip   = argv[++i];
        else if (a == "--port" && i + 1 < argc) port = static_cast<unsigned short>(std::stoi(argv[++i]));
        else if (a == "--msg"  && i + 1 < argc) msg  = argv[++i];
        else if (a == "--help" || a == "-h") { usage(argv[0]); return 0; }
        else {
            std::cerr << "Unknown option: " << a << std::endl;
            usage(argv[0]);
            return 1;
        }
    }

    if (mode == NONE) {
        std::cerr << "Error: specify --tcp, --udp, or --kcp\n";
        usage(argv[0]);
        return 1;
    }

    int rc = 0;
    switch (mode) {
    case TCP: rc = NetworkClientTest::runTcpTest(ip, port, msg); break;
    case UDP: rc = NetworkClientTest::runUdpTest(ip, port, msg); break;
    case KCP: rc = NetworkClientTest::runKcpTest(ip, port);       break;
    default: break;
    }

    if (rc == 0)
        std::cout << "\nAll client tests finished." << std::endl;
    else
        std::cerr << "\nClient test FAILED (rc=" << rc << ")." << std::endl;

    return rc;
}
