/**
 * Network Server Tests — standalone TCP / UDP / KCP server echo endpoints.
 *
 * Build:
 *   cd test/build && cmake .. && make NetworkServerTest
 *
 * Usage:
 *   Terminal 1: ./NetworkServerTest --tcp --port 8800
 *   Terminal 2: ./NetworkClientTest --tcp --ip 127.0.0.1 --port 8800 --msg "hello"
 *
 *   Terminal 1: ./NetworkServerTest --udp --port 8800
 *   Terminal 2: ./NetworkClientTest --udp --ip 127.0.0.1 --port 8800 --msg "hello"
 *
 *   Terminal 1: ./NetworkServerTest --kcp --port 8800
 *   Terminal 2: ./NetworkClientTest --kcp --ip 127.0.0.1 --port 8800
 */

#include "NetworkServerTest.h"
#include "../socket/UdpSocket.h"
#include "../socket/KcpSocket.h"

#include <iostream>
#include <cstring>
#include <thread>
#include <chrono>
#include <signal.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <poll.h>

// ---------------------------------------------------------------------------
// Global flag for graceful shutdown on Ctrl-C
// ---------------------------------------------------------------------------
static volatile sig_atomic_t g_running = 1;
static void sigHandler(int) { g_running = 0; }

// ---------------------------------------------------------------------------
// TCP Echo Server — bind → listen → accept → recv → echo → close
// ---------------------------------------------------------------------------
int NetworkServerTest::runTcpServer(unsigned short port)
{
    signal(SIGINT,  sigHandler);
    signal(SIGTERM, sigHandler);

    int listenFd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (listenFd < 0) {
        perror("[TCP-SRV] socket");
        return -1;
    }

    int opt = 1;
    setsockopt(listenFd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr {};
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons(port);
    addr.sin_addr.s_addr = INADDR_ANY;

    if (::bind(listenFd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        perror("[TCP-SRV] bind");
        close(listenFd);
        return -2;
    }

    if (::listen(listenFd, 5) < 0) {
        perror("[TCP-SRV] listen");
        close(listenFd);
        return -3;
    }

    std::cout << "[TCP-SRV] Listening on 0.0.0.0:" << port << " (Ctrl-C to stop)" << std::endl;

    while (g_running) {
        // Use poll so we can check g_running
        struct pollfd pfd {};
        pfd.fd     = listenFd;
        pfd.events = POLLIN;

        int pr = poll(&pfd, 1, 1000);  // 1-second timeout
        if (pr < 0) {
            if (errno == EINTR) break;
            perror("[TCP-SRV] poll");
            break;
        }
        if (pr == 0 || !(pfd.revents & POLLIN)) {
            continue;  // timeout, re-check g_running
        }

        struct sockaddr_in clientAddr {};
        socklen_t addrLen = sizeof(clientAddr);
        int clientFd = ::accept(listenFd, reinterpret_cast<sockaddr*>(&clientAddr), &addrLen);
        if (clientFd < 0) {
            if (errno == EINTR) break;
            perror("[TCP-SRV] accept");
            continue;
        }

        char ipStr[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &clientAddr.sin_addr, ipStr, sizeof(ipStr));
        unsigned short clientPort = ntohs(clientAddr.sin_port);
        std::cout << "[TCP-SRV] Client connected: " << ipStr << ":" << clientPort << std::endl;

        // Set recv timeout so we don't hang on a silent client
        struct timeval tv {5, 0};
        setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

        char buf[4096] = {};
        ssize_t n = ::recv(clientFd, buf, sizeof(buf) - 1, 0);
        if (n > 0) {
            buf[n] = '\0';
            std::cout << "[TCP-SRV] Received " << n << " bytes: " << buf << std::endl;

            // Echo the data back
            ssize_t sent = ::send(clientFd, buf, n, 0);
            if (sent > 0) {
                std::cout << "[TCP-SRV] Echoed " << sent << " bytes." << std::endl;
            } else {
                perror("[TCP-SRV] send");
            }
        } else if (n == 0) {
            std::cout << "[TCP-SRV] Client closed connection." << std::endl;
        } else {
            perror("[TCP-SRV] recv");
        }

        close(clientFd);
        std::cout << "[TCP-SRV] Client disconnected." << std::endl;
    }

    close(listenFd);
    std::cout << "[TCP-SRV] Server stopped." << std::endl;
    return 0;
}

// ---------------------------------------------------------------------------
// UDP Echo Server — bind → recvfrom → sendto back
// ---------------------------------------------------------------------------
int NetworkServerTest::runUdpServer(unsigned short port)
{
    signal(SIGINT,  sigHandler);
    signal(SIGTERM, sigHandler);

    int sock = ::socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        perror("[UDP-SRV] socket");
        return -1;
    }

    int opt = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr {};
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons(port);
    addr.sin_addr.s_addr = INADDR_ANY;

    if (::bind(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        perror("[UDP-SRV] bind");
        close(sock);
        return -2;
    }

    // Set recv timeout so we can poll for Ctrl-C
    struct timeval tv {1, 0};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    std::cout << "[UDP-SRV] Listening on 0.0.0.0:" << port
              << " (Ctrl-C to stop, or single-shot echo)" << std::endl;

    struct sockaddr_in remote {};
    socklen_t remoteLen = sizeof(remote);
    char buf[4096] = {};

    ssize_t n = ::recvfrom(sock, buf, sizeof(buf) - 1, 0,
                           reinterpret_cast<sockaddr*>(&remote), &remoteLen);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            std::cout << "[UDP-SRV] Timeout — no data received." << std::endl;
        } else {
            perror("[UDP-SRV] recvfrom");
        }
    } else {
        buf[n] = '\0';
        char ipStr[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &remote.sin_addr, ipStr, sizeof(ipStr));
        unsigned short remotePort = ntohs(remote.sin_port);
        std::cout << "[UDP-SRV] Received " << n << " bytes from "
                  << ipStr << ":" << remotePort << ": " << buf << std::endl;

        // Echo the data back to sender
        ssize_t sent = ::sendto(sock, buf, n, 0,
                                reinterpret_cast<sockaddr*>(&remote), remoteLen);
        if (sent > 0) {
            std::cout << "[UDP-SRV] Echoed " << sent << " bytes." << std::endl;
        } else {
            perror("[UDP-SRV] sendto");
        }
    }

    close(sock);
    std::cout << "[UDP-SRV] Server stopped." << std::endl;
    return 0;
}

// ---------------------------------------------------------------------------
// KCP Server — init (server mode) → startServer loop
// ---------------------------------------------------------------------------
int NetworkServerTest::runKcpServer(unsigned short port)
{
    signal(SIGINT,  sigHandler);
    signal(SIGTERM, sigHandler);

    KcpSocket kcp;

    std::cout << "[KCP-SRV] Initializing server on port " << port << " ..." << std::endl;
    int ret = kcp.init(port, false /*server*/);
    if (ret < 0) {
        std::cerr << "[KCP-SRV] init() failed: " << ret << std::endl;
        return -1;
    }
    std::cout << "[KCP-SRV] Init OK, running server loop (Ctrl-C to stop) ..."
              << std::endl;

    // Run server on a background thread; main thread waits for signal
    std::thread worker([&kcp] { kcp.startServer(); });

    // Wait for Ctrl-C or 30 s timeout
    for (int i = 0; i < 30 && g_running; ++i) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }

    std::cout << "[KCP-SRV] Stopping ..." << std::endl;
    kcp.stop();

    if (worker.joinable()) worker.join();

    kcp.destroy();

    std::cout << "[KCP-SRV] Server stopped." << std::endl;
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
              << "  --tcp           Run TCP echo server\n"
              << "  --udp           Run UDP echo server\n"
              << "  --kcp           Run KCP server\n"
              << "\n"
              << "Options:\n"
              << "  --port <port>   Port to listen on (default: 8800)\n"
              << "  --help, -h      Show this help\n"
              << "\n"
              << "Examples:\n"
              << "  Terminal 1: " << prog << " --tcp --port 8800\n"
              << "  Terminal 2: ./NetworkClientTest --tcp --ip 127.0.0.1 --port 8800 --msg hello\n"
              << "\n"
              << "  Terminal 1: " << prog << " --kcp --port 8800\n"
              << "  Terminal 2: ./NetworkClientTest --kcp --ip 127.0.0.1 --port 8800\n"
              << std::endl;
}

int main(int argc, char* argv[])
{
    std::cout << "=== Network Server Test Suite ===" << std::endl;

    enum { NONE, TCP, UDP, KCP } mode = NONE;
    unsigned short port = 8800;

    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        if (a == "--tcp")       mode = TCP;
        else if (a == "--udp")  mode = UDP;
        else if (a == "--kcp")  mode = KCP;
        else if (a == "--port" && i + 1 < argc)
            port = static_cast<unsigned short>(std::stoi(argv[++i]));
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
    case TCP: rc = NetworkServerTest::runTcpServer(port); break;
    case UDP: rc = NetworkServerTest::runUdpServer(port); break;
    case KCP: rc = NetworkServerTest::runKcpServer(port); break;
    default: break;
    }

    if (rc == 0)
        std::cout << "\nServer finished." << std::endl;
    else
        std::cerr << "\nServer FAILED (rc=" << rc << ")." << std::endl;

    return rc;
}
