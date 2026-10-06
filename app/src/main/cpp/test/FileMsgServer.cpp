#include "FileMsgServer.h"
#include "../socket/FileMsgSocket.h"

#include <iostream>
#include <fstream>
#include <thread>
#include <chrono>
#include <random>
#include <mutex>
#include <vector>
#include <algorithm>
#include <cstring>
#include <cstdio>
#include <cerrno>
#include <csignal>
#include <stdexcept>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#ifdef _WIN32
#include <direct.h>
#define MKDIR(path) _mkdir(path)
#else
#include <unistd.h>
#define MKDIR(path) mkdir(path, 0755)
#endif

static std::atomic<bool> g_serverReady(false);
static std::atomic<bool> g_transferComplete(false);

/** server 模式的退出开关：Ctrl-C / kill 置 1，主循环看到就收摊 */
static volatile sig_atomic_t g_stopRequested = 0;
static void onStopSignal(int) { g_stopRequested = 1; }

/** server 模式统计收了几个文件 */
static std::atomic<int> g_receivedCount(0);

/** 服务端完成回调里带的真实落盘路径（接收端文件名带时间戳，不能自己拼） */
static std::mutex g_pathMutex;
static std::string g_receivedPath;

static void noteReceivedPath(const std::string& status)
{
    size_t bar = status.find('|');
    if (bar == std::string::npos) return;
    std::lock_guard<std::mutex> lock(g_pathMutex);
    g_receivedPath = status.substr(bar + 1);
}

static std::string lastReceivedPath()
{
    std::lock_guard<std::mutex> lock(g_pathMutex);
    return g_receivedPath;
}

/** 打印时去掉 "|真实路径" 那段，避免终端里刷一长串绝对路径 */
static std::string visibleStatus(const std::string& status)
{
    size_t bar = status.find('|');
    return (bar == std::string::npos) ? status : status.substr(0, bar);
}

/** 完成类消息把真实落盘路径带在 "|" 后面，纯服务模式下换成箭头打出来更好读 */
static std::string prettyStatus(const std::string& status)
{
    size_t bar = status.find('|');
    if (bar == std::string::npos) return status;
    return status.substr(0, bar) + " -> " + status.substr(bar + 1);
}

// ---------------------------------------------------------------------------
// createTestFile — 生成指定大小的随机二进制文件
// ---------------------------------------------------------------------------
std::string FileMsgServer::createTestFile(const std::string& path, size_t size)
{
    std::ofstream file(path, std::ios::binary);
    if (!file.is_open()) {
        std::cerr << "Failed to create test file: " << path << std::endl;
        return "";
    }

    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<> dis(0, 255);

    const size_t bufferSize = 65536;  // 64KB buffer
    std::vector<char> buffer(bufferSize);

    for (size_t i = 0; i < size; i += bufferSize) {
        size_t chunk = std::min(bufferSize, size - i);
        for (size_t j = 0; j < chunk; ++j) {
            buffer[j] = static_cast<char>(dis(gen));
        }
        file.write(buffer.data(), chunk);
    }

    file.close();
    std::cout << "Created test file: " << path << " (" << size << " bytes)" << std::endl;
    return path;
}

// ---------------------------------------------------------------------------
// compareFiles — 逐字节比对两个文件
// ---------------------------------------------------------------------------
bool FileMsgServer::compareFiles(const std::string& file1, const std::string& file2)
{
    std::ifstream f1(file1, std::ios::binary);
    std::ifstream f2(file2, std::ios::binary);

    if (!f1.is_open() || !f2.is_open()) {
        std::cerr << "Failed to open files for comparison: "
                  << file1 << " / " << file2 << std::endl;
        return false;
    }

    f1.seekg(0, std::ios::end);
    f2.seekg(0, std::ios::end);
    if (f1.tellg() != f2.tellg()) {
        std::cerr << "File sizes differ" << std::endl;
        return false;
    }

    f1.seekg(0, std::ios::beg);
    f2.seekg(0, std::ios::beg);

    const size_t bufferSize = 4096;
    char buffer1[bufferSize], buffer2[bufferSize];

    while (f1 && f2) {
        f1.read(buffer1, bufferSize);
        f2.read(buffer2, bufferSize);
        std::streamsize g1 = f1.gcount();
        std::streamsize g2 = f2.gcount();
        if (g1 == 0 && g2 == 0) break;
        if (g1 != g2 || memcmp(buffer1, buffer2, (size_t)g1) != 0) {
            std::cerr << "File content differs" << std::endl;
            return false;
        }
    }

    std::cout << "Files are identical!" << std::endl;
    return true;
}

// ---------------------------------------------------------------------------
// waitForPort — 轮询目标端口，直到能连上（跨进程判断服务端是否起来了）
// ---------------------------------------------------------------------------
bool FileMsgServer::waitForPort(const std::string& ip, unsigned short port, int timeoutMs)
{
#ifdef _WIN32
    (void)ip; (void)port;
    std::this_thread::sleep_for(std::chrono::milliseconds(timeoutMs > 1000 ? 1000 : timeoutMs));
    return true;
#else
    const int stepMs = 100;
    for (int waited = 0; waited < timeoutMs; waited += stepMs) {
        int sock = socket(AF_INET, SOCK_STREAM, 0);
        if (sock < 0) return false;

        struct sockaddr_in addr {};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(port);
        if (inet_pton(AF_INET, ip.c_str(), &addr.sin_addr) <= 0) {
            close(sock);
            return false;
        }

        int rc = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
        close(sock);
        if (rc == 0) return true;

        std::this_thread::sleep_for(std::chrono::milliseconds(stepMs));
    }
    return false;
#endif
}

// ---------------------------------------------------------------------------
// runServerTest — 只起服务端：持续接收，来一个收一个，Ctrl-C 才停
// ---------------------------------------------------------------------------
void FileMsgServer::runServerTest(unsigned short port)
{
    FileMsgSocket server;
    server.setSavePath("./received");
    MKDIR("./received");

    server.setProgressCallback([](uint64_t current, uint64_t total, const std::string& status) {
        noteReceivedPath(status);
        if (status.find("Transfer complete!") != std::string::npos) {
            g_transferComplete.store(true);
            int n = ++g_receivedCount;
            printf("[Server] #%d %s\n", n, prettyStatus(status).c_str());
            fflush(stdout);
            return;
        }
        // 进度行在这里只是刷屏，纯接收端只需要连接 / 完成 / 断开 / 报错
        if (status.find("Receiving...") != std::string::npos) return;
        const std::string text = visibleStatus(status);
        if (total > 0) {
            printf("[Server] %.1f%% (%llu/%llu) %s\n",
                (double)current / total * 100,
                (unsigned long long)current,
                (unsigned long long)total,
                text.c_str());
        } else {
            printf("[Server] %s\n", text.c_str());
        }
        fflush(stdout);
        });

    std::cout << "Starting server on port " << port << "..." << std::endl;
    if (server.startServer(port) < 0) {
        std::cerr << "Server start failed" << std::endl;
        return;
    }

    g_serverReady.store(true);
    std::cout << "Server started on port " << port
              << " — saving to ./received, Ctrl-C to stop." << std::endl;

    signal(SIGINT, onStopSignal);
    signal(SIGTERM, onStopSignal);
    while (!g_stopRequested) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }

    std::cout << "\nStopping server (" << g_receivedCount.load() << " file(s) received)..."
              << std::endl;
    server.stopServer();
    std::cout << "Server stopped." << std::endl;
}

// ---------------------------------------------------------------------------
// runClientTest — 只跑客户端（服务端可以在另一个进程 / 终端）
// ---------------------------------------------------------------------------
int FileMsgServer::runClientTest(const std::string& ip, unsigned short port,
    const std::string& filePath)
{
    if (!waitForPort(ip, port, 5000)) {
        std::cerr << "Server not reachable at " << ip << ":" << port << ", aborting" << std::endl;
        return -1;
    }

    FileMsgSocket client;
    client.setProgressCallback([](uint64_t current, uint64_t total, const std::string& status) {
        if (total > 0) {
            printf("[Client] %.1f%% (%llu/%llu) %s\n",
                (double)current / total * 100,
                (unsigned long long)current,
                (unsigned long long)total,
                status.c_str());
        } else {
            printf("[Client] %s\n", status.c_str());
        }
        fflush(stdout);
        });

    std::cout << "Connecting to " << ip << ":" << port << "..." << std::endl;
    if (client.connectToServer(ip, port) < 0) {
        std::cerr << "Connection failed" << std::endl;
        return -2;
    }

    std::cout << "Connected, sending file: " << filePath << std::endl;
    if (client.postLocalFile(filePath) < 0) {
        std::cerr << "Send file failed" << std::endl;
        client.disconnect();
        return -3;
    }

    std::cout << "File sent successfully!" << std::endl;
    // 让服务端把最后一片处理完再断开
    std::this_thread::sleep_for(std::chrono::milliseconds(300));
    client.disconnect();
    return 0;
}

// ---------------------------------------------------------------------------
// runIntegrationTest — 单进程跑完 server + client 并校验落盘文件
// ---------------------------------------------------------------------------
bool FileMsgServer::runIntegrationTest(unsigned short port, const std::string& filePath)
{
    MKDIR("./received");

    FileMsgSocket server;
    server.setSavePath("./received");
    server.setProgressCallback([](uint64_t current, uint64_t total, const std::string& status) {
        noteReceivedPath(status);
        if (status.find("Transfer complete!") != std::string::npos) {
            g_transferComplete.store(true);
        }
        const std::string text = visibleStatus(status);
        if (total > 0) {
            printf("[Server] %.1f%% (%llu/%llu) %s\n",
                (double)current / total * 100,
                (unsigned long long)current,
                (unsigned long long)total,
                text.c_str());
        } else {
            printf("[Server] %s\n", text.c_str());
        }
        fflush(stdout);
        });

    std::cout << "\n=== Running integration test (single process) ===" << std::endl;
    if (server.startServer(port) < 0) {
        std::cerr << "Server start failed" << std::endl;
        return false;
    }
    g_serverReady.store(true);

    FileMsgSocket client;
    client.setProgressCallback([](uint64_t current, uint64_t total, const std::string& status) {
        if (total > 0) {
            printf("[Client] %.1f%% (%llu/%llu) %s\n",
                (double)current / total * 100,
                (unsigned long long)current,
                (unsigned long long)total,
                status.c_str());
        } else {
            printf("[Client] %s\n", status.c_str());
        }
        fflush(stdout);
        });

    bool ok = false;
    if (client.connectToServer("127.0.0.1", port) < 0) {
        std::cerr << "Connection failed" << std::endl;
    } else if (client.postLocalFile(filePath) < 0) {
        std::cerr << "Send file failed" << std::endl;
    } else {
        // 等服务端的完成回调：它是在 recvFile.close() 之后发的，
        // 收到就说明文件已经完整落盘了。
        for (int i = 0; i < 200 && !g_transferComplete.load(); ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(25));
        }
        ok = g_transferComplete.load();
    }

    client.disconnect();
    server.stopServer();

    if (!ok) {
        std::cerr << "Transfer did not complete" << std::endl;
        return false;
    }

    std::string received = lastReceivedPath();
    if (received.empty()) {
        std::cerr << "No received file path reported" << std::endl;
        return false;
    }

    std::cout << "\n=== File verification ===" << std::endl;
    std::cout << "sent:     " << filePath << std::endl;
    std::cout << "received: " << received << std::endl;
    return compareFiles(filePath, received);
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------
int main(int argc, char* argv[])
{
    std::cout << "=== FileMsgSocket Test Suite ===" << std::endl;

    unsigned short port = 8800;
    std::string testFilePath = "./test_send.bin";
    std::string ip = "127.0.0.1";
    size_t testFileSize = 1024 * 1024;  // 1MB

    bool serverMode = false;
    bool clientMode = false;
    bool sizeGiven = false;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--server" || arg == "-s") {
            serverMode = true;
        } else if (arg == "--client" || arg == "-c") {
            clientMode = true;
        } else if (arg == "--port" && i + 1 < argc) {
            try {
                port = static_cast<unsigned short>(std::stoi(argv[++i]));
            } catch (const std::exception&) {
                std::cerr << "Invalid port number" << std::endl;
                return 1;
            }
        } else if (arg == "--ip" && i + 1 < argc) {
            ip = argv[++i];
        } else if (arg == "--file" && i + 1 < argc) {
            testFilePath = argv[++i];
        } else if (arg == "--size" && i + 1 < argc) {
            try {
                testFileSize = std::stoull(argv[++i]);
                sizeGiven = true;
            } catch (const std::exception&) {
                std::cerr << "Invalid size" << std::endl;
                return 1;
            }
        } else if (arg == "--help" || arg == "-h") {
            std::cout << "Usage: " << argv[0] << " [options]\n"
                << "Options:\n"
                << "  --server, -s          Run as server only (keeps listening, Ctrl-C to stop)\n"
                << "  --client, -c          Run as client only\n"
                << "  --port <port>         Server port (default: 8800)\n"
                << "  --ip <ip>             Server IP for client (default: 127.0.0.1)\n"
                << "  --file <path>         Test file path (default: ./test_send.bin)\n"
                << "  --size <bytes>        Test file size (default: 1048576)\n"
                << "  --help, -h            Show this help\n\n"
                << "Examples:\n"
                << "  # single-process integration test (server + client + verify)\n"
                << "  " << argv[0] << " --size 1048576\n\n"
                << "  # two terminals\n"
                << "  Terminal 1: " << argv[0] << " --server --port 8800\n"
                << "  Terminal 2: " << argv[0] << " --client --ip 127.0.0.1 --port 8800\n";
            return 0;
        } else {
            std::cerr << "Unknown option: " << arg << std::endl;
            return 1;
        }
    }

    if (serverMode) {
        FileMsgServer::runServerTest(port);
        return 0;
    }

    // 给了 --size 就造测试文件（客户端模式也适用）；默认模式无论如何都造一个
    if (sizeGiven || !clientMode) {
        FileMsgServer::createTestFile(testFilePath, testFileSize);
    }

    if (clientMode) {
        int rc = FileMsgServer::runClientTest(ip, port, testFilePath);
        return rc == 0 ? 0 : 1;
    }

    bool passed = FileMsgServer::runIntegrationTest(port, testFilePath);
    std::cout << (passed ? "TEST PASSED: Files match!" : "TEST FAILED!") << std::endl;
    return passed ? 0 : 1;
}
