#include "FileMsgSocketTest.h"
#include "../socket/FileMsgSocket.h"
#include <iostream>
#include <fstream>
#include <cassert>
#include <thread>
#include <chrono>
#include <random>
#include <sys/stat.h>
#include <vector>
#include <stdexcept>

#ifdef _WIN32
#include <process.h>
#define fork() -1
#define waitpid(pid, status, options) -1
#else
#include <unistd.h>
#include <sys/wait.h>
#endif

static std::atomic<bool> g_serverReady(false);
static std::atomic<bool> g_transferComplete(false);

std::string FileMsgSocketTest::createTestFile(const std::string& path, size_t size)
{
    std::ofstream file(path, std::ios::binary);
    if (!file.is_open()) {
        std::cerr << "Failed to create test file: " << path << std::endl;
        return "";
    }

    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<> dis(0, 255);

    // 使用缓冲区批量写入，提升性能
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

bool FileMsgSocketTest::compareFiles(const std::string& file1, const std::string& file2)
{
    std::ifstream f1(file1, std::ios::binary);
    std::ifstream f2(file2, std::ios::binary);

    if (!f1.is_open() || !f2.is_open()) {
        std::cerr << "Failed to open files for comparison" << std::endl;
        return false;
    }

    // 比较文件大小
    f1.seekg(0, std::ios::end);
    f2.seekg(0, std::ios::end);
    if (f1.tellg() != f2.tellg()) {
        std::cerr << "File sizes differ" << std::endl;
        return false;
    }

    // 比较内容
    f1.seekg(0, std::ios::beg);
    f2.seekg(0, std::ios::beg);

    const size_t bufferSize = 4096;
    char buffer1[bufferSize], buffer2[bufferSize];

    while (f1 && f2) {
        f1.read(buffer1, bufferSize);
        f2.read(buffer2, bufferSize);
        std::streamsize g1 = f1.gcount();
        std::streamsize g2 = f2.gcount();
        if (g1 != g2 || memcmp(buffer1, buffer2, g1) != 0) {
            std::cerr << "File content differs" << std::endl;
            return false;
        }
    }

    std::cout << "Files are identical!" << std::endl;
    return true;
}

void FileMsgSocketTest::runServerTest(unsigned short port)
{
    FileMsgSocket server;
    server.setSavePath("./received");

    // 创建接收目录
    mkdir("./received", 0755);

    server.setProgressCallback([](uint64_t current, uint64_t total, const std::string& status) {
        if (total > 0) {
            printf("[Server] Progress: %.1f%% (%lu/%lu) - %s\n",
                (double)current / total * 100,
                (unsigned long)current,
                (unsigned long)total,
                status.c_str());
        } else {
            printf("[Server] %s\n", status.c_str());
        }
        });

    std::cout << "Starting server on port " << port << "..." << std::endl;
    int ret = server.startServer(port);
    if (ret < 0) {
        std::cerr << "Server start failed: " << ret << std::endl;
        return;
    }

    g_serverReady.store(true);
    std::cout << "Server started successfully, waiting for client..." << std::endl;

    // 等待连接和传输完成
    while (!g_transferComplete.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    // 给一些时间让最后一个数据包写入
    std::this_thread::sleep_for(std::chrono::seconds(1));

    std::cout << "Stopping server..." << std::endl;
    server.stopServer();
    std::cout << "Server test completed." << std::endl;
}

void FileMsgSocketTest::runClientTest(const std::string& ip, unsigned short port, const std::string& filePath)
{
    // 等待服务器准备就绪
    int waitCount = 0;
    while (!g_serverReady.load() && waitCount < 5000) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        waitCount++;
    }

    if (!g_serverReady.load()) {
        std::cerr << "Server not ready, aborting client test" << std::endl;
        return;
    }

    FileMsgSocket client;

    client.setProgressCallback([](uint64_t current, uint64_t total, const std::string& status) {
        if (total > 0) {
            printf("[Client] Progress: %.1f%% (%lu/%lu) - %s\n",
                (double)current / total * 100,
                (unsigned long)current,
                (unsigned long)total,
                status.c_str());
        } else {
            printf("[Client] %s\n", status.c_str());
        }
        });

    std::cout << "Connecting to " << ip << ":" << port << "..." << std::endl;
    int ret = client.connectToServer(ip, port);
    if (ret < 0) {
        std::cerr << "Connection failed: " << ret << std::endl;
        return;
    }

    std::cout << "Connected, sending file: " << filePath << std::endl;
    ret = client.sendFile(filePath);
    if (ret < 0) {
        std::cerr << "Send file failed: " << ret << std::endl;
    } else {
        std::cout << "File sent successfully!" << std::endl;
        g_transferComplete.store(true);
    }

    std::this_thread::sleep_for(std::chrono::seconds(1));
    client.disconnect();
}

int main(int argc, char* argv[])
{
    std::cout << "=== FileMsgSocket Test Suite ===" << std::endl;

    // 默认配置
    unsigned short port = 8800;
    std::string testFileName = "test_send.bin";  // 仅文件名，不含路径
    std::string testFilePath = "./test_send.bin";
    std::string ip = "127.0.0.1";
    size_t testFileSize = 1024 * 1024; // 1MB

    // 解析命令行参数
    bool serverMode = false;
    bool clientMode = false;

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
            // 提取文件名用于接收路径
            size_t pos = testFilePath.find_last_of("/\\");
            testFileName = (pos != std::string::npos) ? testFilePath.substr(pos + 1) : testFilePath;
        } else if (arg == "--size" && i + 1 < argc) {
            try {
                testFileSize = std::stoull(argv[++i]);
            } catch (const std::exception&) {
                std::cerr << "Invalid size" << std::endl;
                return 1;
            }
        } else if (arg == "--help" || arg == "-h") {
            std::cout << "Usage: " << argv[0] << " [options]\n"
                << "Options:\n"
                << "  --server, -s          Run as server\n"
                << "  --client, -c          Run as client\n"
                << "  --port <port>         Server port (default: 8800)\n"
                << "  --ip <ip>             Server IP for client (default: 127.0.0.1)\n"
                << "  --file <path>         Test file path (default: ./test_send.bin)\n"
                << "  --size <bytes>        Test file size (default: 1048576)\n"
                << "  --help, -h            Show this help\n\n"
                << "Example:\n"
                << "  Terminal 1: " << argv[0] << " --server --port 8800\n"
                << "  Terminal 2: " << argv[0] << " --client --ip 127.0.0.1 --port 8800\n";
            return 0;
        }
    }

    // 如果是客户端模式，先创建测试文件
    if (clientMode || (!serverMode && !clientMode)) {
        FileMsgSocketTest::createTestFile(testFilePath, testFileSize);
    }

    // 如果没有指定模式，默认运行完整测试（先server后client）
    if (!serverMode && !clientMode) {
        // 创建子进程分别运行 server 和 client
        std::cout << "\n=== Running full integration test ===" << std::endl;

#ifdef _WIN32
        std::cerr << "Fork not supported on Windows. Please run server and client in separate terminals." << std::endl;
        std::cerr << "Terminal 1: " << argv[0] << " --server --port " << port << std::endl;
        std::cerr << "Terminal 2: " << argv[0] << " --client --ip 127.0.0.1 --port " << port << " --file " << testFilePath << std::endl;
        return 1;
#else
        pid_t pid = fork();
        if (pid < 0) {
            std::cerr << "Fork failed" << std::endl;
            return 1;
        }

        if (pid == 0) {
            // 子进程 - 运行服务器
            FileMsgSocketTest::runServerTest(port);
            exit(0);
        } else {
            // 父进程 - 等待服务器启动，然后运行客户端
            std::this_thread::sleep_for(std::chrono::seconds(1));
            FileMsgSocketTest::runClientTest(ip, port, testFilePath);

            // 等待子进程结束
            int status;
            waitpid(pid, &status, 0);

            // 验证文件 - 使用正确的接收路径
            std::string receivedPath = "./received/" + testFileName;
            struct stat s;
            if (stat(receivedPath.c_str(), &s) == 0) {
                std::cout << "\n=== File verification ===" << std::endl;
                bool match = FileMsgSocketTest::compareFiles(testFilePath, receivedPath);
                if (match) {
                    std::cout << "TEST PASSED: Files match!" << std::endl;
                } else {
                    std::cout << "TEST FAILED: Files differ!" << std::endl;
                    return 1;
                }
            } else {
                std::cerr << "Received file not found: " << receivedPath << std::endl;
                return 1;
            }
        }
#endif
    } else if (serverMode) {
        FileMsgSocketTest::runServerTest(port);
    } else if (clientMode) {
        FileMsgSocketTest::runClientTest(ip, port, testFilePath);
    }

    return 0;
}
