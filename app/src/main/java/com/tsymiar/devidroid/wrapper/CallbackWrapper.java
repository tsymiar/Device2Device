package com.tsymiar.devidroid.wrapper;

import com.tsymiar.devidroid.data.Receiver;

public class CallbackWrapper {
    static {
        System.loadLibrary("jniComm");
    }

    public static native void initJvmEnv(String className);

    public native String stringGetJNI();

    public native Receiver getMessage(Receiver receiver);

    public native long timeSetJNI(byte[] time, int len);

    public static native void callJavaMethod(String method, int action, String content, boolean statics);

    public static native int KaiSubscribe(String address, int port, String topic, String viewId, int id);

    public static native void KaiPublish(String topic, String payload);

    public static native void quitSubscribe();
}
