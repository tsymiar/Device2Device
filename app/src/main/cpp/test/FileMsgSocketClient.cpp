/**
 * FileMsgSocket Client — standalone file-sending client.
 *
 * Connects to a running FileMsgSocket server and sends a file.
 * Unlike FileMsgSocketServer (fork-based), this client runs independently.
 *
 * Build:
 *   cd test/build && cmake .. && make FileMsgSocketClient
 *
 * Usage:
 *   Terminal 1: ./FileMsgSocketServer --server --port 8800
 *   Terminal 2: ./FileMsgSocketClient --ip 127.0.0.1 --port 8800 --file ./test.bin
 *   # Or auto-generate a random file of given size:
 *   Terminal 2: ./FileMsgSocketClient --ip 127.0.0.1 --port 8800 --size 524288
 */

#include "FileMsgSocketClient.h"
#include "../socket/FileMsgSocket.h"

#include <iostream>
#include <fstream>
#include <thread>
#include <chrono>
#include <random>
#include <vector>
#include <sys/stat.h>
#include <cstring>

 // ---------------------------------------------------------------------------
 // createTestFile — generate a binary file with random content
 // ---------------------------------------------------------------------------
std::string FileMsgSocketClient::createTestFile(const std::string& path, size_t size)
{
    std::ofstream file(path, std::ios::binary);
    if (!file.is_open()) {
        std::cerr << "Failed to create test file: " << path << std::endl;
        return "";
    }

    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<> dis(0, 255);

    const size_t kBufSize = 65536;
    std::vector<char> buffer(kBufSize);

    for (size_t i = 0; i < size; i += kBufSize) {
        size_t chunk = std::min(kBufSize, size - i);
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
// sendLocalFile — connect and transfer with progress display
// ---------------------------------------------------------------------------
int FileMsgSocketClient::sendLocalFile(const std::string& ip, unsigned short port,
    const std::string& filePath)
{
    // Verify the file exists
    struct stat st {};
    if (::stat(filePath.c_str(), &st) != 0) {
        std::cerr << "File not found: " << filePath << " (" << strerror(errno) << ")" << std::endl;
        return -1;
    }
    uint64_t fileSize = st.st_size;
    std::cout << "File: " << filePath << " (" << fileSize << " bytes)" << std::endl;

    FileMsgSocket client;

    // Progress callback — print transfer status
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

    // Connect
    std::cout << "Connecting to " << ip << ":" << port << " ..." << std::endl;
    int ret = client.connectToServer(ip, port);
    if (ret < 0) {
        std::cerr << "Connection failed: " << ret << std::endl;
        return -2;
    }
    std::cout << "Connected." << std::endl;

    // Send
    std::cout << "Sending " << filePath << " ..." << std::endl;
    ret = client.sendLocalFile(filePath);
    if (ret < 0) {
        std::cerr << "Send failed: " << ret << std::endl;
        client.disconnect();
        return -3;
    }

    std::cout << "\nFile sent successfully!" << std::endl;

    // Give server time to finalize
    std::this_thread::sleep_for(std::chrono::milliseconds(500));

    client.disconnect();
    return 0;
}

// ---------------------------------------------------------------------------
// main — CLI
// ---------------------------------------------------------------------------
static void usage(const char* prog)
{
    std::cout << "Usage: " << prog << " [options]\n"
        << "\n"
        << "Options:\n"
        << "  --ip   <addr>   Server IP      (default: 127.0.0.1)\n"
        << "  --port <port>   Server port    (default: 8800)\n"
        << "  --file <path>   File to send   (required unless --size is used)\n"
        << "  --size <bytes>  Generate random file of this size and send it\n"
        << "  --out  <name>   Name for auto-generated file (default: test_send.bin)\n"
        << "  --help, -h      Show this help\n"
        << "\n"
        << "Examples:\n"
        << "  Terminal 1: ./FileMsgSocketServer --server --port 8800\n"
        << "  Terminal 2: " << prog << " --ip 127.0.0.1 --port 8800 --file ./data.bin\n"
        << "  Terminal 2: " << prog << " --ip 127.0.0.1 --port 8800 --size 1048576\n"
        << std::endl;
}

int main(int argc, char* argv[])
{
    std::cout << "=== FileMsgSocket Client ===" << std::endl;

    std::string ip = "127.0.0.1";
    unsigned short port = 8800;
    std::string filePath;
    size_t genSize = 0;
    std::string genName = "test_send.bin";   // auto-generated file name

    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        if (a == "--ip" && i + 1 < argc) ip = argv[++i];
        else if (a == "--port" && i + 1 < argc) port = static_cast<unsigned short>(std::stoi(argv[++i]));
        else if (a == "--file" && i + 1 < argc) filePath = argv[++i];
        else if (a == "--size" && i + 1 < argc) genSize = std::stoull(argv[++i]);
        else if (a == "--out" && i + 1 < argc) genName = argv[++i];
        else if (a == "--help" || a == "-h") { usage(argv[0]); return 0; } else {
            std::cerr << "Unknown option: " << a << std::endl;
            usage(argv[0]);
            return 1;
        }
    }

    // If --size specified, generate file first
    if (genSize > 0) {
        filePath = "./" + genName;
        FileMsgSocketClient::createTestFile(filePath, genSize);
    }

    if (filePath.empty()) {
        std::cerr << "Error: specify --file or --size\n";
        usage(argv[0]);
        return 1;
    }

    int rc = FileMsgSocketClient::sendLocalFile(ip, port, filePath);

    if (rc == 0)
        std::cout << "\nFileMsgSocket client finished successfully." << std::endl;
    else
        std::cerr << "\nFileMsgSocket client FAILED (rc=" << rc << ")." << std::endl;

    return rc;
}
