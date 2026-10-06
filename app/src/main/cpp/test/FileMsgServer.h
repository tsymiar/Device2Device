#ifndef DEVICE2DEVICE_FileMsgTest_H
#define DEVICE2DEVICE_FileMsgTest_H

#include <atomic>
#include <string>

class FileMsgServer {
public:
    /**
     * 只起服务端：持续监听，来一个收一个（每收完一个记一行含落盘路径的日志），
     * 直到收到 SIGINT / SIGTERM（Ctrl-C）才收摊。不做任何校验，纯当接收端用。
     */
    static void runServerTest(unsigned short port = 8800);

    /** 只跑客户端：连上服务端后发一个文件（服务端可以在另一个进程 / 终端里）；0=成功 */
    static int runClientTest(const std::string& ip, unsigned short port, const std::string& filePath);

    /**
     * 单进程集成测试：server 与 client 跑在同一个进程里。
     * 不用 fork —— fork 之后父子进程各自持有一份全局标志，
     * 父进程等子进程的 ready、子进程等父进程的 complete，两边都永远等不到。
     */
    static bool runIntegrationTest(unsigned short port, const std::string& filePath);

    static bool compareFiles(const std::string& file1, const std::string& file2);
    static std::string createTestFile(const std::string& path, size_t size);

    /** 轮询目标端口直到能连上（跨进程判断"服务端起来了"用） */
    static bool waitForPort(const std::string& ip, unsigned short port, int timeoutMs = 5000);
};

#endif
