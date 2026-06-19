#ifndef DEVICE2DEVICE_FileMsgSocketClient_H
#define DEVICE2DEVICE_FileMsgSocketClient_H

#include <string>
#include <cstdint>

class FileMsgSocketClient {
public:
    /**
     * Connect to a FileMsgSocket server and send a file.
     *
     * @param ip          Server IP address
     * @param port        Server port
     * @param filePath    Path to the file to send
     * @return 0 on success, negative on error
     */
    static int sendLocalFile(const std::string& ip, unsigned short port, const std::string& filePath);

    /** Generate a random test file of given size */
    static std::string createTestFile(const std::string& path, size_t size);
};

#endif
