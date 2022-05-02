package com.tsymiar.devidroid.wrapper;

public class NetWrapper {
    static {
        System.loadLibrary("jniComm");
    }
    public static native int sendUdpData(String text, int len);
    public static native int startUdpServer();
    public static native int startTcpServer(int port);
    public static native void KcpRun();
}
