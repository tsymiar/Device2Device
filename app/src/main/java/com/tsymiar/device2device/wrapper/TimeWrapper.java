package com.tsymiar.device2device.wrapper;

public class TimeWrapper {
    static {
        System.loadLibrary("jniComm");
    }

    public static native long getAbsoluteTimestamp();

    public static native long getBootTimestamp();
}
