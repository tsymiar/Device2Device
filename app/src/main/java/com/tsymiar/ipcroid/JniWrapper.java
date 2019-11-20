package com.tsymiar.ipcroid;

public class JniWrapper {
    static {
        System.loadLibrary("jniComm");
    }

    public static native void initJvmEnv(String className);

    public native String stringFromJNI();

    public static native void callJavaStaticMethod(String method, int action, String content);

    public static native void callJavaNonstaticMethod(String method, int action, String content);
}
