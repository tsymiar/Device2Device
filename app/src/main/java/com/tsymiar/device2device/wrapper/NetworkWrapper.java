package com.tsymiar.device2device.wrapper;

public class NetworkWrapper {
    static {
        System.loadLibrary("jniComm");
    }

    // TCP
    public static native int startTcpServer(int port);
    public static native int stopTcpServer();

    // UDP
    public static native int startUdpServer(int port);
    public static native int sendUdpData(String text, int len);

    // KCP
    public static native int startKcpServer(int port);
    public static native int stopKcpServer();
    public static native int startKcpClient(String addr, int port);
    public static native int stopKcpClient();
    /** 客户端发一条数据（服务端收到的内容会回传到页面底部 hint 区） */
    public static native int sendKcpData(String text, int len);

    // 文件传输服务器
    public static native int startFileMsgServer(int port);

    // 文件传输客户端
    public static native int connectFileMsgServer(String ip, int port);

    // 断开连接
    public static native void disconnectFileMsg();

    // 发送文件
    public static native int postLocalFile(String filePath);

    // 请求文件
    public static native int requestFile(String ip, int port, String fileName);

    public static native void setFileSavePath(String path);

    // 停止服务器
    public static native void stopFileMsgServer();

    // 查询状态
    public static native boolean isFileMsgConnected();

    // 局域网文本消息：收发走 KCP over UDP（一个绑本端口的 socket，按对端地址取 ikcp 会话直接发，
    // 大消息自动分片），设备发现走 UDP 广播，
    // 发现结果与收发内容通过 Receiver.LAN_PEER / LAN_MESSAGE 回传
    public static native int startLanMessenger(String name, String selfIp, int port);

    public static native void stopLanMessenger();

    public static native int sendLanMessage(String ip, int port, String text);

    /** 图片走同一条链路，body 是 JPEG 转出来的 base64；返回 -4 表示超出上限，需要再压小一点 */
    public static native int sendLanImage(String ip, int port, String base64);

    public static native void refreshLanPeers();

    /** 设置发现用的广播端口（与收发的 KCP 端口分开，默认 39000） */
    public static native void setLanDiscoveryPort(int port);

    /** 设置发现广播目标地址：默认 255.255.255.255（广播），可填 224.224.224.224 等组播或单播地址 */
    public static native void setLanDiscoveryAddress(String address);
}
