package com.tsymiar.device2device.wrapper;

public class NetworkWrapper {
    static {
        System.loadLibrary("jniComm");
    }

    // TCP
    public static native int startTcpServer(int port);

    // UDP
    public static native int startUdpServer(int port);
    public static native int sendUdpData(String text, int len);

    // KCP
    public static native int startKcpServer(int port);
    public static native int startKcpClient(String addr, int port);

    // 文件传输服务器
    public static native int startFileMsgServer(int port);

    // 文件传输客户端
    public static native int connectFileMsgServer(String ip, int port);

    // 断开连接
    public static native void disconnectFileMsg();

    // 发送文件
    public static native int sendFile(String filePath);

    // 请求文件
    public static native int requestFile(String ip, int port, String fileName);

    // 设置保存路径
    public static native void setFileSavePath(String path);

    // 停止服务器
    public static native void stopFileMsgServer();

    // 查询状态
    public static native boolean isFileMsgConnected();
}
