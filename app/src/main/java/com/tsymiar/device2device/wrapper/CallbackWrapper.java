package com.tsymiar.device2device.wrapper;

import com.tsymiar.device2device.entity.Receiver;

public class CallbackWrapper {
    static {
        System.loadLibrary("jniComm");
    }

    public static native void initJvmEnv(String className);

    public native String stringGetJNI();

    public native Receiver getMessage(Receiver receiver);

    public native long timeSetJNI(byte[] time, int len);

    public static native void callJavaMethod(String method, int action, String content, boolean statics);

    public static native int StartSubscribe(String address, int port, String topic, String viewId, int id);

    public static native void Publish(String topic, String payload);

    public static native void QuitSubscribe();
}
