package com.tsymiar.devidroid.wrapper;

public class CallbackWrapper {
    static {
        System.loadLibrary("jniComm");
    }

    public static native void initJvmEnv(String className);

    public native String stringFromJNI();

    public static native void callJavaMethod(String method, int action, String content, boolean statics);
}
