#ifndef DEVICE2DEVICE_FileMsgSocketTest_H
#define DEVICE2DEVICE_FileMsgSocketTest_H

#include <atomic>
#include <string>

class FileMsgSocketTest {
public:
    static void runServerTest(unsigned short port = 8800);
    static void runClientTest(const std::string& ip, unsigned short port, const std::string& filePath);
    static bool compareFiles(const std::string& file1, const std::string& file2);
    static std::string createTestFile(const std::string& path, size_t size);
};

#endif
