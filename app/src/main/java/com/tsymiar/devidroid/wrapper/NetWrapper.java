package com.tsymiar.devidroid.wrapper;

public class NetWrapper {
    static {
        System.loadLibrary("jniComm");
    }
    public static native int sendUdpData(String text, int len);
    public static native int startServer();
    public static native void KcpRun();
}
